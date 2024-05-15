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

package org.wso2.carbon.identity.organization.management.application.listener;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.ApplicationBasicInfo;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationManagementUtil;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.carbon.identity.organization.management.application.OrgApplicationManager;
import org.wso2.carbon.identity.organization.management.application.OrgApplicationManagerImpl;
import org.wso2.carbon.identity.organization.management.application.dao.OrgApplicationMgtDAO;
import org.wso2.carbon.identity.organization.management.application.internal.OrgApplicationMgtDataHolder;
import org.wso2.carbon.identity.organization.management.application.model.MainApplicationDO;
import org.wso2.carbon.identity.organization.management.application.model.SharedApplicationDO;
import org.wso2.carbon.identity.organization.management.application.util.OrgApplicationManagerUtil;
import org.wso2.carbon.identity.organization.management.ext.Constants;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.management.service.model.BasicOrganization;
import org.wso2.carbon.identity.organization.management.service.model.Organization;
import org.wso2.carbon.identity.organization.management.service.util.OrganizationManagementUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.SHARE_WITH_ALL_CHILDREN;
import static org.wso2.carbon.identity.organization.management.application.util.OrgApplicationManagerUtil.setIsAppSharedProperty;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.SUPER_ORG_ID;
import static org.wso2.carbon.identity.organization.management.service.util.Utils.getAuthenticatedUsername;

/**
 * This class contains the implementation of the handler for post organization creation.
 * This handler will be used to add shared applications to newly created organizations.
 */
public class OrganizationCreationHandler extends AbstractEventHandler {

    private static final Log LOG = LogFactory.getLog(OrganizationCreationHandler.class);

    @Override
    public void handleEvent(Event event) throws IdentityEventException {

        String eventName = event.getEventName();

        if (Constants.EVENT_POST_ADD_ORGANIZATION.equals(eventName)) {
            Map<String, Object> eventProperties = event.getEventProperties();
            Organization organization = (Organization) eventProperties.get(Constants.EVENT_PROP_ORGANIZATION);
            try {
                addSharedApplicationsToOrganization(organization);
            } catch (IdentityApplicationManagementException | OrganizationManagementException e) {
                throw new IdentityEventException("An error occurred while creating shared applications in the new " +
                        "organization", e);
            }
        }

        if (Constants.EVENT_PRE_DELETE_ORGANIZATION.equals(eventName)) {
            Map<String, Object> eventProperties = event.getEventProperties();
            String organizationId = (String) eventProperties.get(Constants.EVENT_PROP_ORGANIZATION_ID);
            try {
                handleMainApplicationUpdateForPreDeleteOrganization(organizationId);
            } catch (IdentityApplicationManagementException | OrganizationManagementException e) {
                throw new IdentityEventException("An error occurred while retrieving main applications of " +
                        "fragment applications configured for organization with ID: " + organizationId, e);
            }
        }

        if (Constants.EVENT_POST_DELETE_ORGANIZATION.equals(eventName)) {
            try {
                handleMainApplicationUpdateForPostDeleteOrganization();
            } catch (OrganizationManagementException | IdentityApplicationManagementException e) {
                throw new IdentityEventException("An error occurred while updating main application based " +
                        "on the organizations that it is shared with during an organization deletion.", e);
            }

        }
    }

    private void addSharedApplicationsToOrganization(Organization organization)
            throws IdentityApplicationManagementException, OrganizationManagementException {

        String parentOrgId = organization.getParent().getId();
        if (parentOrgId == null) {
            parentOrgId = SUPER_ORG_ID;
        }

        ApplicationBasicInfo[] applicationBasicInfos;
        applicationBasicInfos = getApplicationManagementService().getAllApplicationBasicInfo(
                getOrganizationManager().resolveTenantDomain(parentOrgId), getAuthenticatedUsername());

        for (ApplicationBasicInfo applicationBasicInfo : applicationBasicInfos) {
            if (CarbonConstants.ENABLE_LEGACY_AUTHZ_RUNTIME &&
                    (FrameworkConstants.Application.CONSOLE_APP.equals(applicationBasicInfo.getApplicationName()) ||
                    FrameworkConstants.Application.MY_ACCOUNT_APP.equals(applicationBasicInfo.getApplicationName()))) {
                continue;
            }
            if (getOrgApplicationMgtDAO().isFragmentApplication(applicationBasicInfo.getApplicationId())) {
                Optional<SharedApplicationDO> sharedApplicationDO;
                sharedApplicationDO = getOrgApplicationMgtDAO().getSharedApplication(
                        applicationBasicInfo.getApplicationId(), parentOrgId);

                if (sharedApplicationDO.isPresent() && sharedApplicationDO.get().shareWithAllChildren()) {
                    Optional<MainApplicationDO> mainApplicationDO;
                    mainApplicationDO = getOrgApplicationMgtDAO().getMainApplication(
                            sharedApplicationDO.get().getFragmentApplicationId(),
                            sharedApplicationDO.get().getOrganizationId());
                    if (mainApplicationDO.isPresent()) {
                        String tenantDomain = getOrganizationManager().resolveTenantDomain(
                                mainApplicationDO.get().getOrganizationId());
                        ServiceProvider mainApplication = getApplicationManagementService()
                                .getApplicationByResourceId(mainApplicationDO.get().getMainApplicationId(),
                                        tenantDomain);
                        String ownerOrgIdOfMainApplication = mainApplicationDO.get().getOrganizationId();
                        getOrgApplicationManager().shareApplication(ownerOrgIdOfMainApplication, organization.getId(),
                                mainApplication, true);
                    }
                }
            } else {
                ServiceProvider mainApplication;
                mainApplication = getApplicationManagementService().getServiceProvider(
                        applicationBasicInfo.getApplicationId());
                if (mainApplication != null && Arrays.stream(mainApplication.getSpProperties())
                        .anyMatch(p -> SHARE_WITH_ALL_CHILDREN.equalsIgnoreCase(
                                p.getName()) && Boolean.parseBoolean(p.getValue()))) {
                    String mainAppOrgId = getOrganizationManager().resolveOrganizationId(mainApplication
                            .getTenantDomain());
                    List<BasicOrganization> applicationSharedOrganizations = getOrgApplicationManager()
                            .getApplicationSharedOrganizations(mainAppOrgId,
                                    mainApplication.getApplicationResourceId());
                    // Having an empty list implies that this is the first organization to which the application is
                    // shared with.
                    boolean updateIsAppSharedProperty = CollectionUtils.isEmpty(applicationSharedOrganizations);
                    getOrgApplicationManager().shareApplication(parentOrgId, organization.getId(),
                            mainApplication, true);
                    if (updateIsAppSharedProperty) {
                        updateApplicationWithIsAppSharedProperty(true, mainApplication);
                    }
                }
            }
        }
    }

    private void handleMainApplicationUpdateForPreDeleteOrganization(String organizationId)
            throws IdentityApplicationManagementException, OrganizationManagementException {

        OrgApplicationManagerUtil.clearB2BApplicationIds();

        String tenantDomain = getOrganizationManager().resolveTenantDomain(organizationId);
        if (!OrganizationManagementUtil.isOrganization(tenantDomain)) {
            return;
        }
        List<String> mainAppIds = new ArrayList<>();
        try {
            String username = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(username);
            ApplicationBasicInfo[] applicationBasicInfos = getApplicationManagementService()
                    .getAllApplicationBasicInfo(tenantDomain, getAuthenticatedUsername());
            for (ApplicationBasicInfo applicationBasicInfo : applicationBasicInfos) {
                ServiceProvider fragmentApplication = getApplicationManagementService().getServiceProvider(
                        applicationBasicInfo.getApplicationId());
                String mainAppId = getApplicationManagementService()
                        .getMainAppId(fragmentApplication.getApplicationResourceId());
                mainAppIds.add(mainAppId);
            }
            OrgApplicationManagerUtil.setB2BApplicationIds(mainAppIds);
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }
    }

    private void handleMainApplicationUpdateForPostDeleteOrganization() throws IdentityApplicationManagementException,
            OrganizationManagementException {

        List<String> mainAppIds = OrgApplicationManagerUtil.getB2BApplicationIds();
        if (CollectionUtils.isEmpty(mainAppIds)) {
            return;
        }
        try {
            // All the applications have the same tenant ID. Therefore, tenant ID of the first application is used.
            int rootTenantId = getApplicationManagementService().getTenantIdByApp(mainAppIds.get(0));
            String rootTenantDomain = IdentityTenantUtil.getTenantDomain(rootTenantId);
            String rootOrganizationId = getOrganizationManager().resolveOrganizationId(rootTenantDomain);
            String username = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(rootTenantDomain, true);
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(username);
            for (String mainAppId : mainAppIds) {
                List<BasicOrganization> applicationSharedOrganizations = getOrgApplicationManager()
                        .getApplicationSharedOrganizations(rootOrganizationId, mainAppId);
                // Since the application doesn't have any shared organizations, isAppShared service provider property
                // should be set to false.
                if (CollectionUtils.isEmpty(applicationSharedOrganizations)) {
                    ServiceProvider mainApplication = getApplicationManagementService()
                            .getApplicationByResourceId(mainAppId, rootTenantDomain);
                    updateApplicationWithIsAppSharedProperty(false, mainApplication);
                }
            }
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
            OrgApplicationManagerUtil.clearB2BApplicationIds();
        }
    }

    private void updateApplicationWithIsAppSharedProperty(boolean isAppShared, ServiceProvider mainApplication)
            throws IdentityApplicationManagementException {

        setIsAppSharedProperty(mainApplication, isAppShared);
        boolean systemApplication = OrgApplicationManagerUtil.isSystemApplication(mainApplication.getApplicationName());
        try {
            if (systemApplication) {
                IdentityApplicationManagementUtil.setAllowUpdateSystemApplicationThreadLocal(true);
            }
            getApplicationManagementService().updateApplication(mainApplication,
                    mainApplication.getTenantDomain(), getAuthenticatedUsername());
        } finally {
            if (systemApplication) {
                IdentityApplicationManagementUtil.removeAllowUpdateSystemApplicationThreadLocal();
            }
        }
    }

    private ApplicationManagementService getApplicationManagementService() {

        return OrgApplicationMgtDataHolder.getInstance().getApplicationManagementService();
    }

    private OrgApplicationManager getOrgApplicationManager() {

        return new OrgApplicationManagerImpl();
    }

    private OrgApplicationMgtDAO getOrgApplicationMgtDAO() {

        return OrgApplicationMgtDataHolder.getInstance().getOrgApplicationMgtDAO();
    }

    private OrganizationManager getOrganizationManager() {

        return OrgApplicationMgtDataHolder.getInstance().getOrganizationManager();
    }

}
