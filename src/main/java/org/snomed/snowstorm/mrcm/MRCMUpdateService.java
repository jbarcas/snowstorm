package org.snomed.snowstorm.mrcm;

import io.kaicode.elasticvc.api.*;
import io.kaicode.elasticvc.domain.Commit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.config.Config;
import org.snomed.snowstorm.core.data.domain.ConceptMini;
import org.snomed.snowstorm.core.data.domain.Concepts;
import org.snomed.snowstorm.core.data.domain.ReferenceSetMember;
import org.snomed.snowstorm.core.data.services.BranchMetadataHelper;
import org.snomed.snowstorm.core.data.services.ConceptService;
import org.snomed.snowstorm.core.data.services.ReferenceSetMemberService;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.mrcm.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.util.CloseableIterator;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static io.kaicode.elasticvc.domain.Commit.CommitType.CONTENT;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;

@Service
public class MRCMUpdateService extends ComponentService implements CommitListener {
	@Autowired
	private MRCMLoader mrcmLoader;

	@Autowired
	private VersionControlHelper versionControlHelper;

	@Autowired
	private ConceptService conceptService;

	@Autowired
	private BranchService branchService;

	@Autowired
	private ReferenceSetMemberService referenceSetMemberService;

	@Autowired
	private BranchMetadataHelper branchMetadataHelper;

	@Autowired
	private ElasticsearchOperations elasticsearchTemplate;

	private Logger logger = LoggerFactory.getLogger(MRCMUpdateService.class);

	static final Comparator<AttributeDomain> ATTRIBUTE_DOMAIN_COMPARATOR_BY_DOMAIN_ID = Comparator
			.comparing(AttributeDomain::getDomainId, Comparator.nullsFirst(String::compareTo));

	static final Comparator<AttributeDomain> ATTRIBUTE_DOMAIN_COMPARATOR_BY_ATTRIBUTE_ID = Comparator
			.comparing(AttributeDomain::getReferencedComponentId, Comparator.nullsFirst(String::compareTo));

	@Override
	public void preCommitCompletion(Commit commit) throws IllegalStateException {
		if (commit.getCommitType() == CONTENT) {
			logger.debug("Start updating MRCM domain templates and attribute rules on branch {}.", commit.getBranch().getPath());
			try {
				performUpdate(false, commit);
				logger.debug("End updating MRCM domain templates and attribute rules on branch {}.", commit.getBranch().getPath());
			} catch (Exception e) {
				throw new IllegalStateException("Failed to update MRCM domain templates and attribute rules." + e, e);
			}
		}
	}

	public void updateAllDomainTemplatesAndAttributeRules(String path) throws ServiceException {
		logger.info("Updating all MRCM domain templates and attribute rules on branch {}.", path);
		try (Commit commit = branchService.openCommit(path, branchMetadataHelper.getBranchLockMetadata("Updating all MRCM components."))) {
			performUpdate(true, commit);
			commit.markSuccessful();
		} catch (Exception e) {
			throw new ServiceException("Failed to update MRCM domain templates and attribute rules for all components.", e);
		}
		logger.info("Completed updating MRCM domain templates and attribute rules for all components on branch {}.", path);
	}

	private List<ReferenceSetMember> updateDomainTemplates(String branchPath, Map<String, Domain> domainMapByDomainId,
												   Map<String, List<AttributeDomain>> domainToAttributesMap,
												   Map<String, List<AttributeRange>> domainToRangesMap,
												   Map<String, String> conceptToTermMap) {

		List<Domain> updatedDomains = generateDomainTemplates(domainMapByDomainId, domainToAttributesMap, domainToRangesMap, conceptToTermMap);
		// run diff report
		runPrecoordinationDiffReport(updatedDomains, domainMapByDomainId);
		runPostcoordinationDiffReport(updatedDomains, domainMapByDomainId);

		Set<String> rangeMemberIds = updatedDomains.stream().map(r -> r.getId()).collect(Collectors.toSet());
		List<ReferenceSetMember> rangeMembers = referenceSetMemberService.findMembers(branchPath, rangeMemberIds);
		Map<String, Domain> memberIdToDomainMap = new HashMap<>();
		List<ReferenceSetMember> toSave = new ArrayList<>();
		for (Domain domain : updatedDomains) {
			memberIdToDomainMap.put(domain.getId(), domain);
		}
		for (ReferenceSetMember member : rangeMembers) {
			member.setAdditionalField("domainTemplateForPrecoordination", memberIdToDomainMap.get(member.getMemberId()).getDomainTemplateForPrecoordination());
			member.setAdditionalField("domainTemplateForPostcoordination", memberIdToDomainMap.get(member.getMemberId()).getDomainTemplateForPostcoordination());
			member.markChanged();
		}
		toSave.addAll(rangeMembers);
		return toSave;

	}

	private void runPrecoordinationDiffReport(List<Domain> updatedDomains, Map<String,Domain> domainMapByDomainId) {
		int sameCounter = 0;
		int sameWhenSortingIngored = 0;
		int diffCounter = 0;
		for (Domain domain : updatedDomains) {
			String domainId = domain.getReferencedComponentId();
			String published = domainMapByDomainId.get(domainId).getDomainTemplateForPrecoordination();
			String actual = domain.getDomainTemplateForPrecoordination();
			if (!published.equals(actual)) {
				logger.info("Analyzing precoordinationdomain template for domain id " + domainId);
				if (hasDiff(published, actual, false)) {
					diffCounter++;
					logger.info("before = " + published);
					logger.info("after = " + actual);
				} else {
					logger.info("domain template is the same when cardinality and sorting is ignored " + domain.getReferencedComponentId());
					sameWhenSortingIngored++;
				}
			} else {
				sameCounter++;
				logger.info("domain template is the same for " + domainId);
			}
		}
		logger.info("Total templates updated = " + updatedDomains.size());
		logger.info("Total templates are the same without change = " + sameCounter);
		logger.info("Total templates are the same when cardinality and sorting is ignored = " + sameWhenSortingIngored);
		logger.info("Total templates found with diffs = " + diffCounter);

	}

	private void runPostcoordinationDiffReport(List<Domain> updatedDomains, Map<String,Domain> domainMapByDomainId) {
		int sameCounter = 0;
		int sameWhenSortingIngored = 0;
		int diffCounter = 0;
		for (Domain domain : updatedDomains) {
			String domainId = domain.getReferencedComponentId();
			String published = domainMapByDomainId.get(domainId).getDomainTemplateForPostcoordination();
			String actual = domain.getDomainTemplateForPostcoordination();
			if (!published.equals(actual)) {
				logger.info("Analyzing postcoordination domain template for domain id " + domainId);
				if (hasDiff(published, actual, false)) {
					diffCounter++;
					logger.info("before = " + published);
					logger.info("after = " + actual);
				} else {
					logger.info("domain template is the same when cardinality and sorting is ignored " + domain.getReferencedComponentId());
					sameWhenSortingIngored++;
				}
			} else {
				sameCounter++;
				logger.info("domain template is the same for " + domainId);
			}
		}
		logger.info("Total templates updated = " + updatedDomains.size());
		logger.info("Total templates are the same without change = " + sameCounter);
		logger.info("Total templates are the same when cardinality and sorting is ignored = " + sameWhenSortingIngored);
		logger.info("Total templates found with diffs = " + diffCounter);
	}

	List<AttributeRange> generateAttributeRule(Map<String, Domain> domainMapByDomainId, Map<String, List<AttributeDomain>> attributeToDomainsMap,
											   Map<String, List<AttributeRange>> attributeToRangesMap,
											   Map<String, String> conceptToFsnMap) {
		List<AttributeRange> updatedRanges = new ArrayList<>();
		// generate attribute rule
		for (String attributeId : attributeToDomainsMap.keySet()) {
			// domain
			List<AttributeDomain> sorted = attributeToDomainsMap.get(attributeId);
			Collections.sort(sorted, ATTRIBUTE_DOMAIN_COMPARATOR_BY_DOMAIN_ID);
			for (AttributeRange range : attributeToRangesMap.get(attributeId)) {
				int counter = 0;
				StringBuilder ruleBuilder = new StringBuilder();
				for (AttributeDomain attributeDomain : sorted) {
					if (RuleStrength.MANDATORY != attributeDomain.getRuleStrength()) {
						continue;
					}
					if (ContentType.ALL != attributeDomain.getContentType() && range.getContentType() != attributeDomain.getContentType()) {
						continue;
					}
					// TODO to make the following code better
					if (counter++ > 0) {
						ruleBuilder.insert(0, "(");
						ruleBuilder.append(")");
						ruleBuilder.append(" OR (");
					}
					String domainConstraint = domainMapByDomainId.get(attributeDomain.getDomainId()).getDomainConstraint().getExpression();
					ruleBuilder.append(domainConstraint);
					if (domainConstraint.contains(":")) {
						ruleBuilder.append(",");
					} else {
						ruleBuilder.append(":");
					}
					// attribute group and attribute cardinality
					if (attributeDomain.isGrouped()) {
						ruleBuilder.append(" [" + attributeDomain.getAttributeCardinality().getValue() + "]" + " {");
						ruleBuilder.append(" [" + attributeDomain.getAttributeInGroupCardinality().getValue() + "]");
					} else {
						ruleBuilder.append(" [" + attributeDomain.getAttributeCardinality().getValue() + "]");
					}

					ruleBuilder.append(" " + attributeId + " |" + conceptToFsnMap.get(attributeId) + "|" + " = ");
					// range constraint
					if (range.getRangeConstraint().contains("OR")) {
						ruleBuilder.append("(" + range.getRangeConstraint() + ")");
					} else {
						ruleBuilder.append(range.getRangeConstraint());
					}
					if (attributeDomain.isGrouped()) {
						ruleBuilder.append(" }");
					}
					if (counter > 1) {
						ruleBuilder.append(")");
					}
				}
				if (!range.getAttributeRule().equals(ruleBuilder.toString())) {
					logger.info("before = " + range.getAttributeRule());
					logger.info("after = " + ruleBuilder.toString());
					AttributeRange updated = new AttributeRange(range);
					updated.setAttributeRule(ruleBuilder.toString());
					updatedRanges.add(updated);
				}
			}
		}
		return updatedRanges;
	}

	private void performUpdate(boolean allComponents, Commit commit) throws IOException, ServiceException {
		String branchPath = commit.getBranch().getPath();
		if (!allComponents) {
			Set<String> mrcmComponentsChangedOnTask =  getMRCMRefsetComponentsChanged(commit);
			if (mrcmComponentsChangedOnTask.isEmpty()) {
				logger.info("No MRCM refset component changes found on branch {}", branchPath);
				return;
			} else {
				logger.info("{} MRCM component changes found on branch {}", mrcmComponentsChangedOnTask.size(), branchPath);
			}
		}

		BranchCriteria branchCriteria = versionControlHelper.getBranchCriteriaIncludingOpenCommit(commit);
		MRCM mrcm = mrcmLoader.loadActiveMRCM(branchPath, branchCriteria);
		Map<String, List<AttributeDomain>> attributeToDomainsMap = new HashMap<>();
		Map<String, List<AttributeDomain>> domainToAttributesMap = new HashMap<>();
		Set<Long> domainIds = new HashSet<>();
		Set<Long> conceptIds = new HashSet<>();
		// map domains by domain id
		Map<String, Domain> domainMapByDomainId = new HashMap<>();
		for (Domain domain : mrcm.getDomains()) {
			domainMapByDomainId.put(domain.getReferencedComponentId(), domain);
		}

		for (AttributeDomain attributeDomain : mrcm.getAttributeDomains()) {
			domainIds.add(new Long(attributeDomain.getDomainId()));
			attributeToDomainsMap.computeIfAbsent(attributeDomain.getReferencedComponentId(), v -> new ArrayList<>()).add(attributeDomain);
			domainToAttributesMap.computeIfAbsent(attributeDomain.getDomainId(), v ->  new ArrayList<>()).add(attributeDomain);
		}
		conceptIds.addAll(domainIds);
		Map<String, List<AttributeRange>> attributeToRangesMap = new HashMap<>();
		for (AttributeRange range : mrcm.getAttributeRanges()) {
			conceptIds.add(new Long(range.getReferencedComponentId()));
			attributeToRangesMap.computeIfAbsent(range.getReferencedComponentId(), ranges -> new ArrayList<>()).add(range);
		}
		// fetch FSN for concepts
		Collection<ConceptMini> conceptMinis = conceptService.findConceptMinis(branchCriteria, conceptIds, Config.DEFAULT_LANGUAGE_DIALECTS).getResultsMap().values();

		Map<String, String> conceptToTermMap = new HashMap<>();
		for (ConceptMini conceptMini : conceptMinis) {
			if (domainIds.contains(Long.valueOf(conceptMini.getConceptId()))) {
				conceptToTermMap.put(conceptMini.getConceptId(), conceptMini.getFsnTerm());
			} else {
				conceptToTermMap.put(conceptMini.getConceptId(), conceptMini.getPt().getTerm());
			}
		}

		List<ReferenceSetMember>  toSave = new ArrayList<>();
		// Attribute rule
		toSave.addAll(updateAttributeRules(branchPath, domainMapByDomainId, attributeToDomainsMap, attributeToRangesMap, conceptToTermMap));
		// domain templates
		toSave.addAll(updateDomainTemplates(branchPath, domainMapByDomainId, domainToAttributesMap, attributeToRangesMap, conceptToTermMap));
		// saving in batch
		logger.info("updating total reference set members " + toSave.size());
		referenceSetMemberService.doSaveBatchMembers(toSave, commit);
	}

	private List<ReferenceSetMember> updateAttributeRules(String branchPath, Map<String,Domain> domainMapByDomainId,
														  Map<String,List<AttributeDomain>> attributeToDomainsMap,
														  Map<String, List<AttributeRange>> attributeToRangesMap,
														  Map<String,String> conceptToTermMap) {

		List<AttributeRange> attributeRanges = generateAttributeRule(domainMapByDomainId, attributeToDomainsMap, attributeToRangesMap, conceptToTermMap);
		logger.info("Total attribute rules updated " + attributeRanges.size());
//		runAttributeRulesDiffReport(attributeRanges, attributeToRangesMap);
		Set<String> rangeMemberIds = attributeRanges.stream().map(r -> r.getId()).collect(Collectors.toSet());
		List<ReferenceSetMember> rangeMembers = referenceSetMemberService.findMembers(branchPath, rangeMemberIds);
		logger.info("Total refset members found " + rangeMembers.size());
		logger.info("refset members found " + rangeMemberIds);
		Map<String, AttributeRange> memberIdToRangeMap = new HashMap<>();
		for (AttributeRange range : attributeRanges) {
			memberIdToRangeMap.put(range.getId(), range);
		}
		List<ReferenceSetMember> updated = new ArrayList<>();
		for (ReferenceSetMember rangeMember : rangeMembers) {
			logger.info("updating member id " + rangeMember.getMemberId());
			logger.info(" rule="  + memberIdToRangeMap.get(rangeMember.getMemberId()).getAttributeRule());
			rangeMember.markChanged();
			updated.add(rangeMember.setAdditionalField("attributeRule", memberIdToRangeMap.get(rangeMember.getMemberId()).getAttributeRule()));
		}
		return updated;
	}

	private void runAttributeRulesDiffReport(List<AttributeRange> attributeRanges, Map<String, List<AttributeRange>> attributeToRangesMap) {
		int sameCounter = 0;
		int sameWhenSortingIngored = 0;
		int diffCounter = 0;
		for (AttributeRange range : attributeRanges) {
			String attributeId = range.getReferencedComponentId();
			String publishedRule = null;
			for (AttributeRange published : attributeToRangesMap.get(attributeId)) {
				if (range.getId().equals(published.getId())) {
					publishedRule = published.getAttributeRule();
					break;
				}
			}
			String actual = range.getAttributeRule();
			if (!actual.equals(publishedRule)) {
				logger.info("Analyzing attribute rule for attribute " + attributeId + " with id = " + range.getId());
				if (hasDiff(publishedRule, actual, true)) {
					diffCounter++;
					logger.info("before = " + publishedRule);
					logger.info("after = " + actual);
				} else {
					logger.info("Attribute rules are the same when cardinality and sorting are ignored " + attributeId);
					sameWhenSortingIngored++;
				}
			} else {
				sameCounter++;
				logger.info("Attribute rule is the same for " + attributeId);
			}
		}
		logger.info("Total templates updated = " + attributeRanges.size());
		logger.info("Total templates are the same without change = " + sameCounter);
		logger.info("Total templates are the same when cardinality and sorting is ignored = " + sameWhenSortingIngored);
		logger.info("Total templates found with diffs = " + diffCounter);
	}

	private Set<String> getMRCMRefsetComponentsChanged(Commit commit) {
		BranchCriteria branchCriteria = versionControlHelper.getBranchCriteriaChangesAndDeletionsWithinOpenCommitOnly(commit);
		Set<String> result = new HashSet<>();
		try (final CloseableIterator<ReferenceSetMember> mrcms = elasticsearchTemplate.stream(new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria.getEntityBranchCriteria(ReferenceSetMember.class))
						.should(termQuery(ReferenceSetMember.Fields.REFSET_ID, Concepts.REFSET_MRCM_DOMAIN_INTERNATIONAL))
						.should(termQuery(ReferenceSetMember.Fields.REFSET_ID, Concepts.REFSET_MRCM_ATTRIBUTE_DOMAIN_INTERNATIONAL))
						.should(termQuery(ReferenceSetMember.Fields.REFSET_ID, Concepts.REFSET_MRCM_ATTRIBUTE_RANGE_INTERNATIONAL))
				)
				.withPageable(ConceptService.LARGE_PAGE)
				.withFields(ReferenceSetMember.Fields.MEMBER_ID)
				.build(), ReferenceSetMember.class)) {
			while (mrcms.hasNext()) {
				result.add(mrcms.next().getMemberId());
			}
		}
		return result;
	}

	List<Domain> generateDomainTemplates(Map<String, Domain> domainsByDomainIdMap, Map<String, List<AttributeDomain>> domainToAttributesMap,
												Map<String, List<AttributeRange>> attributeToRangesMap, Map<String, String> conceptToFsnMap) {

		List<Domain> updatedDomains = new ArrayList<>();
		logger.info(("Checking and updating domain templates for " + domainsByDomainIdMap.keySet()));
		for (String domainId : domainsByDomainIdMap.keySet()) {
			Domain domain = new Domain(domainsByDomainIdMap.get(domainId));
			List<String> parentDomainIds = findParentDomains(domain, domainsByDomainIdMap);
			String precoordinated = generateDomainTemplate(domain, domainsByDomainIdMap, domainToAttributesMap, attributeToRangesMap, conceptToFsnMap, parentDomainIds, ContentType.PRECOORDINATED);
			boolean isChanged = false;
			if (!domain.getDomainTemplateForPrecoordination().equals(precoordinated)) {
				domain.setDomainTemplateForPrecoordination(precoordinated);
				isChanged = true;
			}
			String postoordinated = generateDomainTemplate(domain, domainsByDomainIdMap, domainToAttributesMap, attributeToRangesMap, conceptToFsnMap, parentDomainIds, ContentType.POSTCOORDINATED);
			if (!domain.getDomainTemplateForPostcoordination().equals(postoordinated)) {
				domain.setDomainTemplateForPostcoordination(postoordinated);
				isChanged = true;
			}
			if (isChanged) {
				updatedDomains.add(domain);
			}
		}
		return updatedDomains;
	}

	private List<String> findParentDomains(Domain domain, Map<String, Domain> domainsByDomainIdMap) {
		List<String> result = new ArrayList<>();
		Domain current = domain;
		while (current != null && current.getParentDomain() != null && !current.getParentDomain().isEmpty()) {
			String parentDomain = current.getParentDomain();
			parentDomain = parentDomain.substring(0, parentDomain.indexOf("|")).trim();
			Domain parent = domainsByDomainIdMap.get(parentDomain);
			if (parent == null) {
				throw new IllegalStateException("No domain object found for for " + parentDomain);
			}
			result.add(parent.getReferencedComponentId());
			current = parent;
		}
		return result;
	}


	private String generatePostcoodinateDomainTemplate(Domain domain, Map<String, Domain> domainsByDomainIdMap, Map<String, List<AttributeDomain>> domainToAttributesMap,
										  Map<String, List<AttributeRange>> attributeToRangesMap, Map<String, String> conceptToFsnMap, List<String> parentDomainIds) {

		StringBuilder templateBuilder = new StringBuilder();
		// proximal primitive domain constraint
		if (domain.getProximalPrimitiveConstraint() != null) {
			templateBuilder.append("[[+scg(");
			templateBuilder.append(domain.getProximalPrimitiveConstraint().getExpression());
			templateBuilder.append(")]]:");
		}
		// proximal primitive domain refinement
		if (domain.getProximalPrimitiveRefinement() != null && !domain.getProximalPrimitiveRefinement().isEmpty()) {
			templateBuilder.append(" " + domain.getProximalPrimitiveRefinement() + ", ");
		}
		// Filter for mandatory and all content or all postCoordinated content
		List<String> domainIdsToInclude = new ArrayList<>(parentDomainIds);
		domainIdsToInclude.add(domain.getReferencedComponentId());
		List<AttributeDomain> postCoordinated = new ArrayList<>();
		for (String domainId : domainIdsToInclude) {
			if (domainToAttributesMap.containsKey(domainId)) {
				postCoordinated.addAll(domainToAttributesMap.get(domainId).stream()
						.filter(d -> (RuleStrength.MANDATORY == d.getRuleStrength()) && (ContentType.ALL == d.getContentType() || ContentType.POSTCOORDINATED == d.getContentType()))
						.collect(Collectors.toList()));
			}
		}
		Collections.sort(postCoordinated, ATTRIBUTE_DOMAIN_COMPARATOR_BY_ATTRIBUTE_ID);
		int counter = 0;
		for (AttributeDomain attributeDomain : postCoordinated) {
			if (counter++ > 0) {
				templateBuilder.append(",");
			}
			List<AttributeRange> ranges = attributeToRangesMap.get(attributeDomain.getReferencedComponentId());
			AttributeRange postCoordinatedRange = null;
			for (AttributeRange range : ranges) {
				if (RuleStrength.MANDATORY == range.getRuleStrength()
					&& (ContentType.ALL == range.getContentType() || ContentType.POSTCOORDINATED == range.getContentType())) {
					postCoordinatedRange = range;
					break;
				}
			}
			if (postCoordinatedRange == null) {
				throw new IllegalStateException("No attribute range found for postcoordinated content type");
			}
			templateBuilder.append(" [[");
			templateBuilder.append(attributeDomain.getAttributeCardinality().getValue());
			templateBuilder.append("]] ");
			if (attributeDomain.isGrouped()) {
				templateBuilder.append("{");
				templateBuilder.append(" [[");
				templateBuilder.append(attributeDomain.getAttributeInGroupCardinality().getValue());
				templateBuilder.append("]] ");
			}

			templateBuilder.append(attributeDomain.getReferencedComponentId() + " |" + conceptToFsnMap.get(attributeDomain.getReferencedComponentId()) + "|");
			templateBuilder.append(" = [[+scg(");
			templateBuilder.append(postCoordinatedRange.getRangeConstraint());
			templateBuilder.append(")]]");
			if (attributeDomain.isGrouped()) {
				templateBuilder.append("}");
			}
		}
		return templateBuilder.toString();
	}

	private String generateDomainTemplate(Domain domain, Map<String, Domain> domainsByDomainIdMap, Map<String, List<AttributeDomain>> domainToAttributesMap,
										  Map<String, List<AttributeRange>> attributeToRangesMap, Map<String, String> conceptToFsnMap, List<String> parentDomainIds, ContentType type) {

		StringBuilder templateBuilder = new StringBuilder();
		// proximal primitive domain constraint
		if (domain.getProximalPrimitiveConstraint() != null) {
			if ( ContentType.PRECOORDINATED == type) {
				templateBuilder.append("[[+id(");
			} else {
				templateBuilder.append("[[+scg(");
			}
			templateBuilder.append(domain.getProximalPrimitiveConstraint().getExpression());
			templateBuilder.append(")]]:");
		}
		// proximal primitive domain refinement
		if (domain.getProximalPrimitiveRefinement() != null && !domain.getProximalPrimitiveRefinement().isEmpty()) {
			logger.info("Found domain having ProximalPrimitiveRefinement " + domain.getReferencedComponentId());
			templateBuilder.append(" " + domain.getProximalPrimitiveRefinement() + ", ");
		}
		// Filter for mandatory and all content type or given type
		List<String> domainIdsToInclude = new ArrayList<>(parentDomainIds);
		domainIdsToInclude.add(domain.getReferencedComponentId());
		List<AttributeDomain> attributeDomains = new ArrayList<>();
		for (String domainId : domainIdsToInclude) {
			 if (domainToAttributesMap.containsKey(domainId)) {
				 attributeDomains.addAll(domainToAttributesMap.get(domainId).stream()
						 .filter(d -> (RuleStrength.MANDATORY == d.getRuleStrength()) && (ContentType.ALL == d.getContentType() || type == d.getContentType()))
						 .collect(Collectors.toList()));
			 }
		}
		Collections.sort(attributeDomains, ATTRIBUTE_DOMAIN_COMPARATOR_BY_ATTRIBUTE_ID);
		int counter = 0;
		for (AttributeDomain attributeDomain : attributeDomains) {
			if (counter++ > 0) {
				templateBuilder.append(",");
			}
			List<AttributeRange> ranges = attributeToRangesMap.get(attributeDomain.getReferencedComponentId());
			AttributeRange attributeRange = null;
			for (AttributeRange range : ranges) {
				if (RuleStrength.MANDATORY == range.getRuleStrength()
						&& (ContentType.ALL == range.getContentType() ||  type == range.getContentType())) {
					attributeRange = range;
					break;
				}
			}
			if (attributeRange == null) {
				throw new IllegalStateException("No attribute range found for attribute " + attributeDomain.getReferencedComponentId()
						+ " with content type " + type.getName() + " or " + ContentType.ALL.name());
			}
			templateBuilder.append(" [[");
			templateBuilder.append(attributeDomain.getAttributeCardinality().getValue());
			templateBuilder.append("]] ");
			if (attributeDomain.isGrouped()) {
				templateBuilder.append("{");
				templateBuilder.append(" [[");
				templateBuilder.append(attributeDomain.getAttributeInGroupCardinality().getValue());
				templateBuilder.append("]] ");
			}

			templateBuilder.append(attributeDomain.getReferencedComponentId() + " |" + conceptToFsnMap.get(attributeDomain.getReferencedComponentId()) + "|");
			if (ContentType.PRECOORDINATED == type) {
				templateBuilder.append(" = [[+id(");
			} else {
				templateBuilder.append(" = [[+scg(");
			}
			templateBuilder.append(attributeRange.getRangeConstraint());
			templateBuilder.append(")]]");
			if (attributeDomain.isGrouped()) {
				templateBuilder.append("}");
			}
		}
		return templateBuilder.toString();
	}

	private boolean hasDiff(String published, String actual, boolean ignoreCardinality) {
		boolean hasDiff = false;
		List<String> publishedSorted = split(published, ignoreCardinality);
		List<String> actualSorted = split(actual, ignoreCardinality);

		System.out.println("Published but missing in the new generated");
		for (String token : publishedSorted) {
			if (!actualSorted.contains(token)) {
				System.out.println(token);
				hasDiff = true;
			}
		}

		System.out.println("In the new generated but missing from the published");
		for (String token : actualSorted) {
			if (!publishedSorted.contains(token)) {
				System.out.println(token);
				hasDiff = true;
			}
		}
		return hasDiff;
	}

	private List<String> split(String expression, boolean ignoreCardinality) {
		List<String> result = new ArrayList<>();
		for (String part : expression.split(",", -1)) {
			if (part.contains(":")) {
				result.addAll(Arrays.asList(part.split(":", -1)));
			} else {
				result.add(part.trim());
			}
		}
		if (ignoreCardinality) {
			List<String> updated = new ArrayList<>();
			for (String token : result) {
				if (token.contains("..")) {
					if (token.endsWith("}")) {
						token = token.replace("}", "");
					}
					updated.add(token.substring(token.lastIndexOf("..") + 5, token.length()).trim());
				}
			}
			result = updated;
		}
		Collections.sort(result);
		return result;
	}
}