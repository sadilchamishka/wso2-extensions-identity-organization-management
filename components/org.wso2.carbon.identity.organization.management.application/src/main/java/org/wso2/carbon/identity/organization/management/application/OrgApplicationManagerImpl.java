/*
 * Copyright (c) 2022, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.organization.management.application;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.AuthenticationStep;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.application.common.model.FederatedAuthenticatorConfig;
import org.wso2.carbon.identity.application.common.model.IdentityProvider;
import org.wso2.carbon.identity.application.common.model.InboundAuthenticationConfig;
import org.wso2.carbon.identity.application.common.model.InboundAuthenticationRequestConfig;
import org.wso2.carbon.identity.application.common.model.LocalAndOutboundAuthenticationConfig;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.model.ServiceProviderProperty;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationConstants;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;
import org.wso2.carbon.identity.claim.metadata.mgt.ClaimMetadataManagementService;
import org.wso2.carbon.identity.claim.metadata.mgt.exception.ClaimMetadataException;
import org.wso2.carbon.identity.claim.metadata.mgt.model.AttributeMapping;
import org.wso2.carbon.identity.claim.metadata.mgt.model.ExternalClaim;
import org.wso2.carbon.identity.claim.metadata.mgt.model.LocalClaim;
import org.wso2.carbon.identity.core.ServiceURL;
import org.wso2.carbon.identity.core.ServiceURLBuilder;
import org.wso2.carbon.identity.core.URLBuilderException;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth.IdentityOAuthAdminException;
import org.wso2.carbon.identity.oauth.OAuthAdminServiceImpl;
import org.wso2.carbon.identity.oauth.common.OAuthConstants;
import org.wso2.carbon.identity.oauth.dto.OAuthConsumerAppDTO;
import org.wso2.carbon.identity.oauth.dto.ScopeDTO;
import org.wso2.carbon.identity.organization.management.application.dao.OrgApplicationMgtDAO;
import org.wso2.carbon.identity.organization.management.application.internal.OrgApplicationMgtDataHolder;
import org.wso2.carbon.identity.organization.management.application.model.SharedApplicationDO;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementServerException;
import org.wso2.carbon.identity.organization.management.service.model.BasicOrganization;
import org.wso2.carbon.identity.organization.management.service.model.Organization;
import org.wso2.carbon.idp.mgt.IdentityProviderManagementException;
import org.wso2.carbon.idp.mgt.IdpManager;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.common.User;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static java.util.Arrays.stream;
import static org.wso2.carbon.identity.application.mgt.ApplicationConstants.AUTH_TYPE_DEFAULT;
import static org.wso2.carbon.identity.application.mgt.ApplicationConstants.AUTH_TYPE_FLOW;
import static org.wso2.carbon.identity.base.IdentityConstants.OpenId.OPENID;
import static org.wso2.carbon.identity.base.IdentityConstants.SKIP_CONSENT;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.AUTH_TYPE_OAUTH_2;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.DELETE_FRAGMENT_APPLICATION;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.DELETE_SHARE_FOR_MAIN_APPLICATION;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.IS_FRAGMENT_APP;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.OIDC_CLAIM_DIALECT_URI;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.ORGANIZATION_LOGIN_AUTHENTICATOR;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.SHARE_WITH_ALL_CHILDREN;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.TENANT;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.UPDATE_SP_METADATA_SHARE_WITH_ALL_CHILDREN;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.USER_ORGANIZATION_CLAIM;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.USER_ORGANIZATION_CLAIM_URI;
import static org.wso2.carbon.identity.organization.management.application.util.OrgApplicationManagerUtil.setShareWithAllChildrenProperty;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_APPLICATION_NOT_SHARED;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_ADMIN_USER_NOT_FOUND_FOR_ORGANIZATION;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_CREATING_OAUTH_APP;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_CREATING_ORG_LOGIN_IDP;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_REMOVING_FRAGMENT_APP;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_RESOLVING_SHARED_APPLICATION;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_RESOLVING_TENANT_DOMAIN_FROM_ORGANIZATION_DOMAIN;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_APPLICATION;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_ORGANIZATION_IDP_LIST;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_SHARING_APPLICATION;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_UPDATING_APPLICATION;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_UPDATING_APPLICATION_ATTRIBUTE;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_INVALID_APPLICATION;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_INVALID_DELETE_SHARE_REQUEST;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_UNAUTHORIZED_APPLICATION_SHARE;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_UNAUTHORIZED_FRAGMENT_APP_ACCESS;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.SUPER_ORG_ID;
import static org.wso2.carbon.identity.organization.management.service.util.Utils.getAuthenticatedUsername;
import static org.wso2.carbon.identity.organization.management.service.util.Utils.getOrganizationId;
import static org.wso2.carbon.identity.organization.management.service.util.Utils.getTenantDomain;
import static org.wso2.carbon.identity.organization.management.service.util.Utils.handleClientException;
import static org.wso2.carbon.identity.organization.management.service.util.Utils.handleServerException;
import static org.wso2.carbon.user.core.UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME;

/**
 * Service implementation to process applications across organizations. Class implements {@link OrgApplicationManager}.
 */
public class OrgApplicationManagerImpl implements OrgApplicationManager {

    private static final Log LOG = LogFactory.getLog(OrgApplicationManagerImpl.class);

    private final ExecutorService executorService = Executors.newFixedThreadPool(1);

    @Override
    public void shareOrganizationApplication(String ownerOrgId, String originalAppId, boolean shareWithAllChildren,
                                             List<String> sharedOrgs) throws OrganizationManagementException {

        String requestInvokingOrganizationId = getOrganizationId();
        if (requestInvokingOrganizationId == null) {
            requestInvokingOrganizationId = SUPER_ORG_ID;
        }
        validateApplicationShareAccess(requestInvokingOrganizationId, ownerOrgId);
        Organization organization = getOrganizationManager().getOrganization(ownerOrgId, false, false);
        String ownerTenantDomain = getTenantDomain();
        ServiceProvider rootApplication = getOrgApplication(originalAppId, ownerTenantDomain);

        List<BasicOrganization> childOrganizations = getOrganizationManager().getChildOrganizations(ownerOrgId, true);
        // Filter the child organization in case user send a list of organizations to share the original application.
        List<BasicOrganization> filteredChildOrgs = shareWithAllChildren ?
                childOrganizations :
                (CollectionUtils.isNotEmpty(sharedOrgs) ?
                        childOrganizations.stream().filter(o -> sharedOrgs.contains(o.getId())).
                                collect(Collectors.toList()) :
                        Collections.emptyList());

        // check if share with all children property needs to be updated.
        boolean updateShareWithAllChildren = shouldUpdateShareWithAllChildren(shareWithAllChildren, rootApplication);

        if (updateShareWithAllChildren) {
            try {
                IdentityUtil.threadLocalProperties.get().put(UPDATE_SP_METADATA_SHARE_WITH_ALL_CHILDREN, true);
                setShareWithAllChildrenProperty(rootApplication, shareWithAllChildren);
                getApplicationManagementService().updateApplication(rootApplication,
                        ownerTenantDomain, getAuthenticatedUsername());
                getOrgApplicationMgtDAO().updateShareWithAllChildren(rootApplication.getApplicationResourceId(),
                        ownerOrgId, shareWithAllChildren);
            } catch (IdentityApplicationManagementException e) {
                throw handleServerException(ERROR_CODE_ERROR_UPDATING_APPLICATION_ATTRIBUTE, e, originalAppId);
            } finally {
                IdentityUtil.threadLocalProperties.get().remove(UPDATE_SP_METADATA_SHARE_WITH_ALL_CHILDREN);
            }
        }

        if (shareWithAllChildren || !filteredChildOrgs.isEmpty()) {
            // Adding federated_org custom oidc claim to the root application reside organization.
            addUserOrganizationOIDCClaim(ownerTenantDomain);
            // Adding Organization login IDP to the root application.
            modifyRootApplication(rootApplication, ownerTenantDomain);
        }

        for (BasicOrganization child : filteredChildOrgs) {
            Organization childOrg = getOrganizationManager().getOrganization(child.getId(), false, false);
            if (TENANT.equalsIgnoreCase(childOrg.getType())) {
                CompletableFuture.runAsync(() -> {
                    try {
                        shareApplication(organization.getId(), childOrg.getId(), rootApplication,
                                shareWithAllChildren);
                    } catch (OrganizationManagementException e) {
                        LOG.error(String.format("Error in sharing application: %s to organization: %s",
                                rootApplication.getApplicationID(), childOrg.getId()), e);
                    }
                }, executorService);
            }
        }
    }

    private void addUserOrganizationOIDCClaim(String tenantDomain) throws OrganizationManagementServerException {

        try {
            Optional<LocalClaim> optionalLocalClaim = getClaimMetadataManagementService().getLocalClaims(tenantDomain)
                    .stream().filter(localClaim -> USER_ORGANIZATION_CLAIM_URI.equals(localClaim.getClaimURI()))
                    .findAny();
            if (!optionalLocalClaim.isPresent()) {
                List<AttributeMapping> attributeMappings = new ArrayList<>();
                attributeMappings.add(new AttributeMapping(PRIMARY_DEFAULT_DOMAIN_NAME,
                        USER_ORGANIZATION_CLAIM));
                Map<String, String> claimProperties = new HashMap<>();
                claimProperties.put("DisplayName", "Organization");
                claimProperties.put("Description", "Local claim for user organization identifier");
                getClaimMetadataManagementService().addLocalClaim(new LocalClaim(USER_ORGANIZATION_CLAIM_URI,
                        attributeMappings, claimProperties), tenantDomain);
            }

            Optional<ExternalClaim> optionalExternalClaim = getClaimMetadataManagementService()
                    .getExternalClaims(OIDC_CLAIM_DIALECT_URI, tenantDomain).stream()
                    .filter(externalClaim -> USER_ORGANIZATION_CLAIM.equals(externalClaim.getClaimURI()))
                    .findAny();
            if (!optionalExternalClaim.isPresent()) {
                getClaimMetadataManagementService().addExternalClaim(new ExternalClaim(OIDC_CLAIM_DIALECT_URI,
                        USER_ORGANIZATION_CLAIM, USER_ORGANIZATION_CLAIM_URI), tenantDomain);
            }

            ScopeDTO scopeDTO = getOAuthAdminService().getScope(OPENID);
            Optional<String> optionalOIDCClaim = stream(scopeDTO.getClaim())
                    .filter(USER_ORGANIZATION_CLAIM::equals).findAny();
            if (!optionalOIDCClaim.isPresent()) {
                List<String> claimList = new ArrayList<>(Arrays.asList(scopeDTO.getClaim()));
                claimList.add(USER_ORGANIZATION_CLAIM);
                scopeDTO = new ScopeDTO(scopeDTO.getName(), scopeDTO.getDisplayName(), scopeDTO.getDescription(),
                        claimList.toArray(new String[0]));
                getOAuthAdminService().updateScope(scopeDTO);
            }
        } catch (ClaimMetadataException | IdentityOAuthAdminException e) {
            throw handleServerException(ERROR_CODE_ERROR_UPDATING_APPLICATION_ATTRIBUTE, e);
        }
    }

    @Override
    public void deleteSharedApplication(String organizationId, String applicationId, String sharedOrganizationId)
            throws OrganizationManagementException {

        validateFragmentApplicationAccess(getOrganizationId(), organizationId);
        ServiceProvider serviceProvider = getOrgApplication(applicationId, getTenantDomain());

        if (sharedOrganizationId == null) {

            // Delete share for all shared applications.
            List<SharedApplicationDO> sharedApplicationDOList =
                    getOrgApplicationMgtDAO().getSharedApplications(organizationId, applicationId);
            for (SharedApplicationDO sharedApplicationDO : sharedApplicationDOList) {
                IdentityUtil.threadLocalProperties.get().put(DELETE_SHARE_FOR_MAIN_APPLICATION, true);
                deleteSharedApplication(serviceProvider, organizationId, sharedApplicationDO.getOrganizationId());
                IdentityUtil.threadLocalProperties.get().remove(DELETE_SHARE_FOR_MAIN_APPLICATION);
            }
            if (Arrays.stream(serviceProvider.getSpProperties()).anyMatch(p ->
                    SHARE_WITH_ALL_CHILDREN.equals(p.getName()) && Boolean.parseBoolean(p.getValue()))) {
                setShareWithAllChildrenProperty(serviceProvider, false);
                IdentityUtil.threadLocalProperties.get().put(UPDATE_SP_METADATA_SHARE_WITH_ALL_CHILDREN, true);
                try {
                    getApplicationManagementService().updateApplication(
                            serviceProvider, getTenantDomain(), getAuthenticatedUsername());
                } catch (IdentityApplicationManagementException e) {
                    throw handleServerException(ERROR_CODE_ERROR_UPDATING_APPLICATION_ATTRIBUTE, e, applicationId);
                } finally {
                    IdentityUtil.threadLocalProperties.get().remove(UPDATE_SP_METADATA_SHARE_WITH_ALL_CHILDREN);
                }
            }
        } else {
            if (Arrays.stream(serviceProvider.getSpProperties())
                    .anyMatch(p -> SHARE_WITH_ALL_CHILDREN.equals(p.getName()) && Boolean.parseBoolean(p.getValue()))) {
                throw handleClientException(ERROR_CODE_INVALID_DELETE_SHARE_REQUEST,
                        serviceProvider.getApplicationResourceId(), sharedOrganizationId);
            }
            deleteSharedApplication(serviceProvider, organizationId, sharedOrganizationId);
        }
    }

    private void deleteSharedApplication(ServiceProvider serviceProvider, String organizationId,
                                         String sharedOrganizationId) throws OrganizationManagementException {

        Optional<String> fragmentApplicationId =
                resolveSharedApp(serviceProvider.getApplicationResourceId(), organizationId, sharedOrganizationId);

        if (fragmentApplicationId.isPresent()) {
            try {
                String sharedTenantDomain = getOrganizationManager().resolveTenantDomain(sharedOrganizationId);
                ServiceProvider fragmentApplication =
                        getApplicationManagementService().getApplicationByResourceId(fragmentApplicationId.get(),
                                sharedTenantDomain);
                String username = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();

                // Setting the thread local property to allow deleting fragment application. Otherwise
                // FragmentApplicationMgtListener will reject application deletion.
                IdentityUtil.threadLocalProperties.get().put(DELETE_FRAGMENT_APPLICATION, true);
                getApplicationManagementService().deleteApplication(fragmentApplication.getApplicationName(),
                        sharedTenantDomain, username);
            } catch (IdentityApplicationManagementException e) {
                throw handleServerException(ERROR_CODE_ERROR_REMOVING_FRAGMENT_APP, e, fragmentApplicationId.get(),
                        sharedOrganizationId);
            } finally {
                IdentityUtil.threadLocalProperties.get().remove(DELETE_FRAGMENT_APPLICATION);
                IdentityUtil.threadLocalProperties.get().remove(DELETE_SHARE_FOR_MAIN_APPLICATION);
            }
        }
    }

    @Override
    public List<BasicOrganization> getApplicationSharedOrganizations(String organizationId, String applicationId)
            throws OrganizationManagementException {

        ServiceProvider application = getOrgApplication(applicationId, getTenantDomain());
        List<SharedApplicationDO> sharedApps =
                getOrgApplicationMgtDAO().getSharedApplications(organizationId, application.getApplicationResourceId());

        List<String> sharedOrganizationIds = sharedApps.stream().map(SharedApplicationDO::getOrganizationId).collect(
                Collectors.toList());

        List<BasicOrganization> organizations = getOrganizationManager().getChildOrganizations(organizationId, true);

        return organizations.stream().filter(o -> sharedOrganizationIds.contains(o.getId())).collect(
                Collectors.toList());
    }

    @Override
    public ServiceProvider resolveSharedApplication(String mainAppName, String ownerOrgId, String sharedOrgId)
            throws OrganizationManagementException {

        String ownerTenantDomain = getOrganizationManager().resolveTenantDomain(ownerOrgId);
        if (StringUtils.isBlank(ownerTenantDomain)) {
            throw handleServerException(ERROR_CODE_ERROR_RESOLVING_TENANT_DOMAIN_FROM_ORGANIZATION_DOMAIN, null,
                    ownerOrgId);
        }

        ServiceProvider mainApplication;
        try {
            mainApplication = Optional.ofNullable(
                            getApplicationManagementService().getServiceProvider(mainAppName, ownerTenantDomain))
                    .orElseThrow(() -> handleClientException(ERROR_CODE_INVALID_APPLICATION, mainAppName));
        } catch (IdentityApplicationManagementException e) {
            throw handleServerException(ERROR_CODE_ERROR_RESOLVING_SHARED_APPLICATION, e, mainAppName, ownerOrgId);
        }

        String sharedAppId =
                resolveSharedApp(mainApplication.getApplicationResourceId(), ownerOrgId, sharedOrgId).orElseThrow(
                        () -> handleClientException(ERROR_CODE_APPLICATION_NOT_SHARED,
                                mainApplication.getApplicationResourceId(), ownerOrgId));
        String sharedOrgTenantDomain = getOrganizationManager().resolveTenantDomain(sharedOrgId);
        try {
            return getApplicationManagementService().getApplicationByResourceId(sharedAppId, sharedOrgTenantDomain);
        } catch (IdentityApplicationManagementException e) {
            throw handleServerException(ERROR_CODE_ERROR_RESOLVING_SHARED_APPLICATION, e, mainAppName, ownerOrgId);
        }
    }

    /**
     * Retrieve the application ({@link ServiceProvider}) for the given identifier and the tenant domain.
     *
     * @param applicationId application identifier.
     * @param tenantDomain  tenant domain.
     * @return instance of {@link ServiceProvider}.
     * @throws OrganizationManagementException on errors when retrieving the application
     */
    private ServiceProvider getOrgApplication(String applicationId, String tenantDomain)
            throws OrganizationManagementException {

        ServiceProvider application;
        try {
            application = getApplicationManagementService().getApplicationByResourceId(applicationId,
                    tenantDomain);
        } catch (IdentityApplicationManagementException e) {
            throw handleServerException(ERROR_CODE_ERROR_RETRIEVING_APPLICATION, e, applicationId);
        }
        return Optional.ofNullable(application)
                .orElseThrow(() -> handleClientException(ERROR_CODE_INVALID_APPLICATION, applicationId));
    }

    /**
     * This method will update the root application by adding the organization login authenticator and updating the
     * claim configurations to enable use of local subject identifier for JIT provisioned users. Also update the root
     * application with federated_org oidc claim as requested claim of the application.
     */
    private void modifyRootApplication(ServiceProvider rootApplication, String tenantDomain)
            throws OrganizationManagementServerException {

        LocalAndOutboundAuthenticationConfig outboundAuthenticationConfig =
                rootApplication.getLocalAndOutBoundAuthenticationConfig();
        AuthenticationStep[] authSteps = outboundAuthenticationConfig.getAuthenticationSteps();

        if (StringUtils.equalsIgnoreCase(outboundAuthenticationConfig.getAuthenticationType(), AUTH_TYPE_DEFAULT)) {
            // We need to set the default tenant authentication sequence.
            if (LOG.isDebugEnabled()) {
                LOG.debug("Authentication type is set to 'DEFAULT'. Reading the authentication sequence from the " +
                        "'default' application and showing the effective authentication sequence for application " +
                        "with id: " + rootApplication.getApplicationResourceId());
            }
            LocalAndOutboundAuthenticationConfig defaultAuthenticationConfig = getDefaultAuthenticationConfig();
            if (defaultAuthenticationConfig != null) {
                authSteps = defaultAuthenticationConfig.getAuthenticationSteps();
            }
            // Change the authType to flow, since we are adding organization login authenticator.
            outboundAuthenticationConfig = new LocalAndOutboundAuthenticationConfig();
            outboundAuthenticationConfig.setAuthenticationType(AUTH_TYPE_FLOW);
        }

        AuthenticationStep first = new AuthenticationStep();
        if (ArrayUtils.isNotEmpty(authSteps)) {
            AuthenticationStep exist = authSteps[0];
            boolean idpAlreadyConfigured =
                    stream(first.getFederatedIdentityProviders()).map(
                                    IdentityProvider::getDefaultAuthenticatorConfig)
                            .anyMatch(auth -> ORGANIZATION_LOGIN_AUTHENTICATOR.equals(auth.getName()));
            if (idpAlreadyConfigured) {
                return;
            }
            first.setStepOrder(exist.getStepOrder());
            first.setSubjectStep(exist.isSubjectStep());
            first.setAttributeStep(exist.isAttributeStep());
            first.setFederatedIdentityProviders(exist.getFederatedIdentityProviders());
            first.setLocalAuthenticatorConfigs(exist.getLocalAuthenticatorConfigs());
        }

        AuthenticationStep[] newAuthSteps =
                ArrayUtils.isNotEmpty(authSteps) ? authSteps.clone() : new AuthenticationStep[1];

        IdentityProvider[] idps;
        try {
            idps = getApplicationManagementService().getAllIdentityProviders(tenantDomain);
        } catch (IdentityApplicationManagementException e) {
            throw handleServerException(ERROR_CODE_ERROR_RETRIEVING_ORGANIZATION_IDP_LIST, e, getOrganizationId());
        }
        Optional<IdentityProvider> maybeOrganizationIDP = stream(idps).filter(this::isOrganizationLoginIDP).findFirst();
        IdentityProvider identityProvider;
        try {
            identityProvider = maybeOrganizationIDP.isPresent() ? maybeOrganizationIDP.get() :
                    getIdentityProviderManager().addIdPWithResourceId(createOrganizationLoginIDP(), tenantDomain);
        } catch (IdentityProviderManagementException e) {
            throw handleServerException(ERROR_CODE_ERROR_CREATING_ORG_LOGIN_IDP, e, getOrganizationId());
        }

        first.setFederatedIdentityProviders(
                (IdentityProvider[]) ArrayUtils.addAll(first.getFederatedIdentityProviders(),
                        new IdentityProvider[]{identityProvider}));
        newAuthSteps[0] = first;
        outboundAuthenticationConfig.setAuthenticationSteps(newAuthSteps);
        rootApplication.setLocalAndOutBoundAuthenticationConfig(outboundAuthenticationConfig);

        // Enabling use of local subject id for provisioned users.
        rootApplication.getClaimConfig().setAlwaysSendMappedLocalSubjectId(true);

        addUserOrganizationApplicationClaim(rootApplication);
        try {
            getApplicationManagementService().updateApplication(rootApplication, tenantDomain,
                    getAuthenticatedUsername());
        } catch (IdentityApplicationManagementException e) {
            throw handleServerException(ERROR_CODE_ERROR_UPDATING_APPLICATION, e,
                    rootApplication.getApplicationResourceId());
        }
    }

    private void addUserOrganizationApplicationClaim(ServiceProvider application) {

        ClaimMapping[] claimMappings = application.getClaimConfig().getClaimMappings();
        Optional<ClaimMapping> optionalClaimMapping = stream(claimMappings)
                .filter(claimMapping -> USER_ORGANIZATION_CLAIM_URI.equals(claimMapping.getLocalClaim().getClaimUri()))
                .findAny();
        if (!optionalClaimMapping.isPresent()) {
            List<ClaimMapping> claimMappingList = new ArrayList<>(Arrays.asList(claimMappings));
            claimMappingList.add(ClaimMapping.build(USER_ORGANIZATION_CLAIM_URI, USER_ORGANIZATION_CLAIM_URI,
                    null, true));
            application.getClaimConfig().setClaimMappings(claimMappingList.toArray(new ClaimMapping[0]));
        }
    }

    private boolean isOrganizationLoginIDP(IdentityProvider idp) {

        FederatedAuthenticatorConfig[] federatedAuthenticatorConfigs = idp.getFederatedAuthenticatorConfigs();
        return ArrayUtils.isNotEmpty(federatedAuthenticatorConfigs) &&
                ORGANIZATION_LOGIN_AUTHENTICATOR.equals(federatedAuthenticatorConfigs[0].getName());
    }

    private IdentityProvider createOrganizationLoginIDP() {

        FederatedAuthenticatorConfig authConfig = new FederatedAuthenticatorConfig();
        authConfig.setName(ORGANIZATION_LOGIN_AUTHENTICATOR);
        authConfig.setDisplayName(ORGANIZATION_LOGIN_AUTHENTICATOR);
        authConfig.setEnabled(true);

        IdentityProvider idp = new IdentityProvider();
        idp.setIdentityProviderName("Organization Login");
        idp.setPrimary(false);
        idp.setFederationHub(false);
        idp.setIdentityProviderDescription("Identity provider for Organization Login.");
        idp.setHomeRealmId("OrganizationLogin");
        idp.setDefaultAuthenticatorConfig(authConfig);
        idp.setFederatedAuthenticatorConfigs(new FederatedAuthenticatorConfig[]{authConfig});

        return idp;
    }

    @Override
    public void shareApplication(String ownerOrgId, String sharedOrgId, ServiceProvider mainApplication,
                                 boolean shareWithAllChildren) throws OrganizationManagementException {

        try {
            String ownerTenantDomain = getOrganizationManager().resolveTenantDomain(ownerOrgId);
            // Use tenant of the organization to whom the application getting shared. When the consumer application is
            // loaded, tenant domain will be derived from the user who created the application.
            String sharedTenantDomain = getOrganizationManager().resolveTenantDomain(sharedOrgId);
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(sharedTenantDomain, true);
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setOrganizationId(sharedOrgId);
            int tenantId = IdentityTenantUtil.getTenantId(sharedTenantDomain);

            try {
                String adminUserId =
                        getRealmService().getTenantUserRealm(tenantId).getRealmConfiguration().getAdminUserId();
                if (StringUtils.isBlank(adminUserId)) {
                    // If realms were not migrated after https://github.com/wso2/product-is/issues/14001.
                    adminUserId = getRealmService().getTenantUserRealm(tenantId)
                            .getRealmConfiguration().getAdminUserName();
                }
                String finalAdminUserId = adminUserId;
                User user = OrgApplicationMgtDataHolder.getInstance()
                        .getOrganizationUserResidentResolverService()
                        .resolveUserFromResidentOrganization(null, adminUserId, sharedOrgId)
                        .orElseThrow(
                                () -> handleServerException(ERROR_CODE_ERROR_ADMIN_USER_NOT_FOUND_FOR_ORGANIZATION,
                                        null, finalAdminUserId));
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(user.getDomainQualifiedUsername());
            } catch (UserStoreException e) {
                throw handleServerException(ERROR_CODE_ERROR_SHARING_APPLICATION, e,
                        mainApplication.getApplicationResourceId(), sharedOrgId);
            }

            Optional<String> mayBeSharedAppId = resolveSharedApp(
                    mainApplication.getApplicationResourceId(), ownerOrgId, sharedOrgId);
            if (mayBeSharedAppId.isPresent()) {
                return;
            }
            // Create Oauth consumer app to redirect login to shared (fragment) application.
            OAuthConsumerAppDTO createdOAuthApp;
            try {
                createdOAuthApp = createOAuthApplication(mainApplication.getApplicationName(), ownerTenantDomain);
            } catch (URLBuilderException | IdentityOAuthAdminException e) {
                throw handleServerException(ERROR_CODE_ERROR_CREATING_OAUTH_APP, e,
                        mainApplication.getApplicationResourceId(), sharedOrgId);
            }

            try {
                ServiceProvider delegatedApplication = prepareSharedApplication(mainApplication, createdOAuthApp);
                String sharedApplicationId = getApplicationManagementService().createApplication(delegatedApplication,
                        sharedOrgId, getAuthenticatedUsername());
                getOrgApplicationMgtDAO().addSharedApplication(mainApplication.getApplicationResourceId(), ownerOrgId,
                        sharedApplicationId, sharedOrgId, shareWithAllChildren);
            } catch (IdentityApplicationManagementException e) {
                removeOAuthApplication(createdOAuthApp);
                throw handleServerException(ERROR_CODE_ERROR_SHARING_APPLICATION, e,
                        mainApplication.getApplicationResourceId(), sharedOrgId);
            }
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }
    }

    private Optional<String> resolveSharedApp(String mainAppId, String ownerOrgId, String sharedOrgId)
            throws OrganizationManagementException {

        return getOrgApplicationMgtDAO().getSharedApplicationResourceId(mainAppId, ownerOrgId, sharedOrgId);
    }

    private OAuthConsumerAppDTO createOAuthApplication(String mainAppName, String tenantDomain)
            throws URLBuilderException, IdentityOAuthAdminException {

        ServiceURL commonAuthServiceUrl =
                ServiceURLBuilder.create().setTenant(tenantDomain).addPath(FrameworkConstants.COMMONAUTH).build();
        String callbackUrl = commonAuthServiceUrl.getAbsolutePublicURL();

        OAuthConsumerAppDTO consumerApp = new OAuthConsumerAppDTO();
        String clientId = UUID.randomUUID().toString();
        consumerApp.setOauthConsumerKey(clientId);
        consumerApp.setOAuthVersion(OAuthConstants.OAuthVersions.VERSION_2);
        consumerApp.setGrantTypes(OAuthConstants.GrantTypes.AUTHORIZATION_CODE);
        consumerApp.setCallbackUrl(callbackUrl);
        consumerApp.setApplicationName(mainAppName);
        return getOAuthAdminService().registerAndRetrieveOAuthApplicationData(consumerApp);
    }

    private void removeOAuthApplication(OAuthConsumerAppDTO oauthApp)
            throws OrganizationManagementException {

        try {
            getOAuthAdminService().removeOAuthApplicationData(oauthApp.getOauthConsumerKey());
        } catch (IdentityOAuthAdminException e) {
            throw handleServerException(ERROR_CODE_ERROR_SHARING_APPLICATION, e, oauthApp.getOauthConsumerKey());
        }
    }

    private ServiceProvider prepareSharedApplication(ServiceProvider mainApplication,
                                                     OAuthConsumerAppDTO oAuthConsumerApp) {

        // Obtain oauth consumer app configs.
        InboundAuthenticationRequestConfig inboundAuthenticationRequestConfig =
                new InboundAuthenticationRequestConfig();
        inboundAuthenticationRequestConfig.setInboundAuthType(AUTH_TYPE_OAUTH_2);
        inboundAuthenticationRequestConfig.setInboundAuthKey(oAuthConsumerApp.getOauthConsumerKey());
        InboundAuthenticationConfig inboundAuthConfig = new InboundAuthenticationConfig();
        inboundAuthConfig.setInboundAuthenticationRequestConfigs(
                new InboundAuthenticationRequestConfig[]{inboundAuthenticationRequestConfig});

        ServiceProvider delegatedApplication = new ServiceProvider();
        delegatedApplication.setApplicationName(oAuthConsumerApp.getApplicationName());
        delegatedApplication.setDescription("Delegated access from: " + mainApplication.getApplicationName());
        delegatedApplication.setInboundAuthenticationConfig(inboundAuthConfig);
        appendFragmentAppProperties(delegatedApplication);

        return delegatedApplication;
    }

    private void appendFragmentAppProperties(ServiceProvider serviceProvider) {

        ServiceProviderProperty fragmentAppProperty = new ServiceProviderProperty();
        fragmentAppProperty.setName(IS_FRAGMENT_APP);
        fragmentAppProperty.setValue(Boolean.TRUE.toString());

        ServiceProviderProperty skipConsentProp = new ServiceProviderProperty();
        skipConsentProp.setName(SKIP_CONSENT);
        skipConsentProp.setValue(Boolean.TRUE.toString());

        ServiceProviderProperty[] spProperties = new ServiceProviderProperty[]{fragmentAppProperty, skipConsentProp};
        serviceProvider.setSpProperties(spProperties);
    }

    /**
     * Allow sharing application only from the organization the application exists.
     *
     * @param requestInvokingOrganizationId     The organization qualifier id where the request is authorized to access.
     * @param applicationResidingOrganizationId The id of the organization where the application exist.
     * @throws OrganizationManagementException The exception is thrown when the request invoked organization is not the
     *                                         application resides organization.
     */
    private void validateApplicationShareAccess(String requestInvokingOrganizationId,
                                                String applicationResidingOrganizationId)
            throws OrganizationManagementException {

        if (!StringUtils.equals(requestInvokingOrganizationId, applicationResidingOrganizationId)) {
            throw handleClientException(ERROR_CODE_UNAUTHORIZED_APPLICATION_SHARE, applicationResidingOrganizationId,
                    requestInvokingOrganizationId);
        }
    }

    /**
     * Allow managing fragment application only from the organization the fragment application exists.
     *
     * @param requestInvokingOrganizationId     The organization qualifier id where the request is authorized to access.
     * @param applicationResidingOrganizationId The id of the organization where the fragment application exist.
     * @throws OrganizationManagementException The exception is thrown when the request invoked organization is not
     *                                         the fragment application resides organization.
     */
    private void validateFragmentApplicationAccess(String requestInvokingOrganizationId,
                                                   String applicationResidingOrganizationId)
            throws OrganizationManagementException {

        if (requestInvokingOrganizationId == null ||
                !StringUtils.equals(requestInvokingOrganizationId, applicationResidingOrganizationId)) {
            throw handleClientException(ERROR_CODE_UNAUTHORIZED_FRAGMENT_APP_ACCESS, applicationResidingOrganizationId,
                    requestInvokingOrganizationId);
        }
    }

    /**
     * Check if the shareWithAllChildren property in the application should be updated or not.
     *
     * @param shareWithAllChildren Attribute indicating if the application is shared with all sub-organizations.
     * @param mainApplication      Main Application
     * @return if the shareWithAllChildren property in the main application should be updated
     */
    private boolean shouldUpdateShareWithAllChildren(boolean shareWithAllChildren, ServiceProvider mainApplication) {

        // If shareWithAllChildren is true and in the main application there is no shareWithAllChildren property,
        // then the value should be updated.
        if (shareWithAllChildren && !(stream(mainApplication.getSpProperties()).anyMatch(
                p -> SHARE_WITH_ALL_CHILDREN.equals(p.getName())))) {
            return true;
        }

        // If shareWithAllChildren is true and in the main application it is set as false,
        // then the value should be updated.
        if (shareWithAllChildren && stream(mainApplication.getSpProperties()).anyMatch(
                p -> SHARE_WITH_ALL_CHILDREN.equals(p.getName()) && !Boolean.parseBoolean(p.getValue()))) {
            return true;
        }

        // If shareWithAllChildren is false and in the main application it is set as true,
        // then the value should be updated.
        if (!shareWithAllChildren && stream(mainApplication.getSpProperties()).anyMatch(
                p -> SHARE_WITH_ALL_CHILDREN.equals(p.getName()) && Boolean.parseBoolean(p.getValue()))) {
            return true;
        }

        return false;
    }

    private OAuthAdminServiceImpl getOAuthAdminService() {

        return OrgApplicationMgtDataHolder.getInstance().getOAuthAdminService();
    }

    private OrganizationManager getOrganizationManager() {

        return OrgApplicationMgtDataHolder.getInstance().getOrganizationManager();
    }

    private ApplicationManagementService getApplicationManagementService() {

        return OrgApplicationMgtDataHolder.getInstance().getApplicationManagementService();
    }

    private OrgApplicationMgtDAO getOrgApplicationMgtDAO() {

        return OrgApplicationMgtDataHolder.getInstance().getOrgApplicationMgtDAO();
    }

    private RealmService getRealmService() {

        return OrgApplicationMgtDataHolder.getInstance().getRealmService();
    }

    private IdpManager getIdentityProviderManager() {

        return OrgApplicationMgtDataHolder.getInstance().getIdpManager();
    }

    private ClaimMetadataManagementService getClaimMetadataManagementService() {

        return OrgApplicationMgtDataHolder.getInstance().getClaimMetadataManagementService();
    }

    private LocalAndOutboundAuthenticationConfig getDefaultAuthenticationConfig()
            throws OrganizationManagementServerException {

        ServiceProvider defaultSP = getDefaultServiceProvider();
        return defaultSP != null ? defaultSP.getLocalAndOutBoundAuthenticationConfig() : null;
    }

    private ServiceProvider getDefaultServiceProvider() throws OrganizationManagementServerException {

        try {
            return OrgApplicationMgtDataHolder.getInstance().getApplicationManagementService()
                    .getServiceProvider(IdentityApplicationConstants.DEFAULT_SP_CONFIG,
                            MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
        } catch (IdentityApplicationManagementException e) {
            throw new OrganizationManagementServerException("Error while retrieving default service provider", null, e);
        }
    }
}
