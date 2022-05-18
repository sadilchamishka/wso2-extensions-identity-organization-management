/*
 * Copyright (c) 2022, WSO2 Inc. (http://www.wso2.com).
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
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

package org.wso2.carbon.identity.organization.management.role.management.service;

import org.apache.commons.lang.StringUtils;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.base.IdentityException;
import org.wso2.carbon.identity.core.model.ExpressionNode;
import org.wso2.carbon.identity.core.model.FilterTreeBuilder;
import org.wso2.carbon.identity.core.model.Node;
import org.wso2.carbon.identity.core.model.OperationNode;
import org.wso2.carbon.identity.organization.management.role.management.service.dao.RoleManagementDAO;
import org.wso2.carbon.identity.organization.management.role.management.service.exception.RoleManagementClientException;
import org.wso2.carbon.identity.organization.management.role.management.service.exception.RoleManagementException;
import org.wso2.carbon.identity.organization.management.role.management.service.internal.RoleManagementDataHolder;
import org.wso2.carbon.identity.organization.management.role.management.service.models.PatchOperation;
import org.wso2.carbon.identity.organization.management.role.management.service.models.Role;
import org.wso2.carbon.identity.organization.management.role.management.service.util.Utils;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.wso2.carbon.identity.organization.management.role.management.service.constant.RoleManagementConstants.AFTER;
import static org.wso2.carbon.identity.organization.management.role.management.service.constant.RoleManagementConstants.AND;
import static org.wso2.carbon.identity.organization.management.role.management.service.constant.RoleManagementConstants.BEFORE;
import static org.wso2.carbon.identity.organization.management.role.management.service.constant.RoleManagementConstants.ErrorMessages.ERROR_CODE_INVALID_CURSOR_FOR_PAGINATION;
import static org.wso2.carbon.identity.organization.management.role.management.service.constant.RoleManagementConstants.ErrorMessages.ERROR_CODE_INVALID_FILTER_FORMAT;
import static org.wso2.carbon.identity.organization.management.role.management.service.constant.RoleManagementConstants.ErrorMessages.ERROR_CODE_INVALID_ORGANIZATION;
import static org.wso2.carbon.identity.organization.management.role.management.service.constant.RoleManagementConstants.ErrorMessages.ERROR_CODE_INVALID_ROLE;
import static org.wso2.carbon.identity.organization.management.role.management.service.constant.RoleManagementConstants.ErrorMessages.ERROR_CODE_ROLE_NAME_ALREADY_EXISTS;
import static org.wso2.carbon.identity.organization.management.role.management.service.constant.RoleManagementConstants.ErrorMessages.ERROR_CODE_UNSUPPORTED_COMPLEX_QUERY_IN_FILTER;
import static org.wso2.carbon.identity.organization.management.role.management.service.constant.RoleManagementConstants.ErrorMessages.ERROR_CODE_UNSUPPORTED_FILTER_ATTRIBUTE;
import static org.wso2.carbon.identity.organization.management.role.management.service.constant.RoleManagementConstants.ROLE_ID_FIELD;
import static org.wso2.carbon.identity.organization.management.role.management.service.constant.RoleManagementConstants.ROLE_NAME_FIELD;

/**
 * Implementation of Role Manager Interface.
 */
public class RoleManagerImpl implements RoleManager {

    @Override
    public Role addRole(String organizationId, Role role) throws RoleManagementException {

        try {
            boolean checkOrganizationExists = getOrganizationManager().isOrganizationExistById(organizationId);
            if (!checkOrganizationExists) {
                throw Utils.handleClientException(ERROR_CODE_INVALID_ORGANIZATION, organizationId);
            }
            boolean checkRoleNameExists = getRoleManagementDAO().checkRoleExists(organizationId, null,
                    StringUtils.strip(role.getName()));
            if (checkRoleNameExists) {
                throw Utils.handleClientException(ERROR_CODE_ROLE_NAME_ALREADY_EXISTS, role.getName());
            }
            getRoleManagementDAO().addRole(organizationId, Utils.getTenantId(), role);
            return new Role(role.getId(), role.getName());
        } catch (OrganizationManagementException e) {
            throw new RoleManagementException(e.getMessage(), e.getDescription(), e.getErrorCode(), e);
        }
    }

    @Override
    public Role getRoleById(String organizationId, String roleId) throws RoleManagementException {

        try {
            validateOrganizationAndRoleId(organizationId, roleId);
            return getRoleManagementDAO().getRoleById(organizationId, roleId, Utils.getTenantId());
        } catch (OrganizationManagementException e) {
            throw new RoleManagementException(e.getMessage(), e.getDescription(), e.getErrorCode(), e);
        }
    }

    @Override
    public List<Role> getOrganizationRoles(int limit, String after, String before, String sortOrder, String filter,
                                           String organizationId) throws RoleManagementException {

        try {
            boolean checkOrganizationExists = getOrganizationManager().isOrganizationExistById(organizationId);
            if (!checkOrganizationExists) {
                throw Utils.handleClientException(ERROR_CODE_INVALID_ORGANIZATION, organizationId);
            }
            return getRoleManagementDAO().getOrganizationRoles(organizationId, sortOrder, Utils.getTenantId(), limit,
                    getExpressionNodes(filter, after, before));
        } catch (OrganizationManagementException e) {
            throw new RoleManagementException(e.getMessage(), e.getDescription(), e.getErrorCode(), e);
        }
    }

    @Override
    public Role patchRole(String organizationId, String roleId, List<PatchOperation> patchOperations)
            throws RoleManagementException {

        try {
            validateOrganizationAndRoleId(organizationId, roleId);
            return getRoleManagementDAO().patchRole(organizationId, roleId, Utils.getTenantId(), patchOperations);
        } catch (OrganizationManagementException e) {
            throw new RoleManagementException(e.getMessage(), e.getDescription(), e.getErrorCode(), e);
        }
    }

    @Override
    public Role putRole(String organizationId, String roleId, Role role) throws RoleManagementException {

        try {
            validateOrganizationAndRoleId(organizationId, roleId);
            return getRoleManagementDAO().putRole(organizationId, roleId, role, Utils.getTenantId());
        } catch (OrganizationManagementException e) {
            throw new RoleManagementException(e.getMessage(), e.getDescription(), e.getErrorCode(), e);
        }
    }

    @Override
    public void deleteRole(String organizationId, String roleId) throws RoleManagementException {

        try {
            validateOrganizationAndRoleId(organizationId, roleId);
            getRoleManagementDAO().deleteRole(organizationId, roleId);
        } catch (OrganizationManagementException e) {
            throw new RoleManagementException(e.getMessage(), e.getDescription(), e.getErrorCode(), e);
        }
    }

    /**
     * Get an instance of RoleManagementDAO.
     *
     * @return An instance of RoleManagementDAO.
     */
    private RoleManagementDAO getRoleManagementDAO() {

        return RoleManagementDataHolder.getInstance().getRoleManagementDAO();
    }

    /**
     * Get an instance of OrganizationManager
     */
    private OrganizationManager getOrganizationManager() {

        return (OrganizationManager) PrivilegedCarbonContext.getThreadLocalCarbonContext()
                .getOSGiService(OrganizationManager.class, null);
    }

    /**
     * Getting the expression nodes for cursor-based pagination.
     *
     * @param filter The filter.
     * @param after  The next pointer to the page.
     * @param before The previous pointer to the page.
     * @return The list of expression nodes.
     * @throws RoleManagementClientException Throw an exception if an erroneous value is passed.
     */
    private List<ExpressionNode> getExpressionNodes(String filter, String after, String before)
            throws RoleManagementClientException {

        List<ExpressionNode> expressionNodes = new ArrayList<>();
        if (StringUtils.isBlank(filter)) {
            filter = StringUtils.EMPTY;
        }
        String paginatedFilter = getPaginatedFilter(filter, after, before);
        try {
            if (StringUtils.isNotBlank(paginatedFilter)) {
                FilterTreeBuilder filterTreeBuilder = new FilterTreeBuilder(paginatedFilter);
                Node rootNode = filterTreeBuilder.buildTree();
                setExpressionNodeList(rootNode, expressionNodes);
            }
        } catch (IOException | IdentityException e) {
            throw Utils.handleClientException(ERROR_CODE_INVALID_FILTER_FORMAT);
        }
        return expressionNodes;
    }

    /**
     * Getting the paginated filter.
     *
     * @param paginatedFilter The paginated filter.
     * @param after           The next pointer to page.
     * @param before          The previous pointer to page.
     * @return The paginated filter.
     * @throws RoleManagementClientException Throw an exception if an erroneous value is passed.
     */
    private String getPaginatedFilter(String paginatedFilter, String after, String before) throws
            RoleManagementClientException {

        try {
            //pagination is done with the uuid(role id) comparison
            if (StringUtils.isNotBlank(before)) {
                String decodedString = new String(Base64.getDecoder().decode(before), StandardCharsets.UTF_8);
                paginatedFilter += StringUtils.isNotBlank(paginatedFilter) ? " and before gt " + decodedString :
                        "before gt " + decodedString;
            } else if (StringUtils.isNotBlank(after)) {
                String decodedString = new String(Base64.getDecoder().decode(after), StandardCharsets.UTF_8);
                paginatedFilter += StringUtils.isNotBlank(paginatedFilter) ? " and after lt " + decodedString :
                        "after lt " + decodedString;
            }
        } catch (IllegalArgumentException e) {
            throw Utils.handleClientException(ERROR_CODE_INVALID_CURSOR_FOR_PAGINATION);
        }
        return paginatedFilter;
    }

    /**
     * Set the expressions to nodes.
     *
     * @param node       The node object.
     * @param expression The list of expression nodes.
     * @throws RoleManagementClientException Throw an exception if an erroneous value is passed.
     */
    private void setExpressionNodeList(Node node, List<ExpressionNode> expression) throws
            RoleManagementClientException {

        if (node instanceof ExpressionNode) {
            ExpressionNode expressionNode = (ExpressionNode) node;
            String attributeValue = expressionNode.getAttributeValue();
            if (StringUtils.isNotBlank(attributeValue)) {
                if (isFilteringAttributeNotSupported(attributeValue)) {
                    throw Utils.handleClientException(ERROR_CODE_UNSUPPORTED_FILTER_ATTRIBUTE, attributeValue);
                }
                expression.add(expressionNode);
            }
        } else if (node instanceof OperationNode) {
            String operation = ((OperationNode) node).getOperation();
            if (!StringUtils.equalsIgnoreCase(operation, AND)) {
                throw Utils.handleClientException(ERROR_CODE_UNSUPPORTED_COMPLEX_QUERY_IN_FILTER);
            }
            setExpressionNodeList(node.getLeftNode(), expression);
            setExpressionNodeList(node.getRightNode(), expression);
        }
    }

    /**
     * Check whether the filtering can be applied to the attributes.
     *
     * @param attributeValue The attribute value.
     * @return Returns true if the filtering attribute is neither the id nor the name.
     */
    private boolean isFilteringAttributeNotSupported(String attributeValue) {

        return !attributeValue.equalsIgnoreCase(ROLE_ID_FIELD) && !attributeValue.equalsIgnoreCase(ROLE_NAME_FIELD) &&
                !attributeValue.equalsIgnoreCase(BEFORE) && !attributeValue.equalsIgnoreCase(AFTER);
    }

    /**
     * Check whether the organization ID and role ID exists.
     *
     * @param organizationId The ID of the organization.
     * @param roleId         The ID of the role.
     * @return True if both organization ID and role ID exist.
     * @throws RoleManagementException         This exception is thrown if any error occurs while checking the
     *                                         validity of the role id.
     * @throws OrganizationManagementException This exception is thrown if any error occurs while checking the
     *                                         validity of the organization id.
     */
    private boolean validateOrganizationAndRoleId(String organizationId, String roleId) throws
            RoleManagementException, OrganizationManagementException {

        boolean checkOrganizationExists = getOrganizationManager().isOrganizationExistById(organizationId);
        if (!checkOrganizationExists) {
            throw Utils.handleClientException(ERROR_CODE_INVALID_ORGANIZATION, organizationId);
        }
        boolean checkRoleIdExists = getRoleManagementDAO().checkRoleExists(organizationId, null,
                StringUtils.strip(roleId));
        if (!checkRoleIdExists) {
            throw Utils.handleClientException(ERROR_CODE_INVALID_ROLE, roleId);
        }
        return checkRoleIdExists && checkOrganizationExists;
    }
}
