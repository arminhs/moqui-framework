/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.webapp

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.NotificationMessage
import org.moqui.context.NotificationMessageListener
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.util.concurrent.ConcurrentHashMap

@CompileStatic
class NotificationWebSocketListener implements NotificationMessageListener {
    private final static Logger logger = LoggerFactory.getLogger(NotificationWebSocketListener.class)

    private ExecutionContextFactory ecf = null
    private ConcurrentHashMap<String, ConcurrentHashMap<String, NotificationEndpoint>> endpointsByUserTenant = new ConcurrentHashMap<>()

    void registerEndpoint(NotificationEndpoint endpoint) {
        String userTenant = endpoint.userId
        String sessionId = endpoint.session.id
        if (userTenant == null) return
        userTenant = userTenant.concat(endpoint.tenantId)
        ConcurrentHashMap<String, NotificationEndpoint> registeredEndPoints = endpointsByUserTenant.get(userTenant)
        if (registeredEndPoints == null) {
            registeredEndPoints = new ConcurrentHashMap<>()
            ConcurrentHashMap<String, NotificationEndpoint> existing = endpointsByUserTenant.putIfAbsent(userTenant, registeredEndPoints)
            if (existing != null) registeredEndPoints = existing
        }
        NotificationEndpoint existing = registeredEndPoints.putIfAbsent(sessionId, endpoint)
        if (existing != null) logger.warn("Found existing NotificationEndpoint for user ${endpoint.userId} (${existing.username}) in tenant ${endpoint.tenantId}, session ${sessionId}; not registering additional endpoint")
    }
    void deregisterEndpoint(NotificationEndpoint endpoint) {
        String userTenant = endpoint.userId
        String sessionId = endpoint.session.id
        if (userTenant == null) return
        userTenant = userTenant.concat(endpoint.tenantId)
        ConcurrentHashMap<String, NotificationEndpoint> registeredEndPoints = endpointsByUserTenant.get(userTenant)
        if (registeredEndPoints == null) {
            logger.warn("Tried to deregister endpoing for user ${endpoint.userId} in tenant ${endpoint.tenantId} but no endpoints found")
            return
        }
        registeredEndPoints.remove(sessionId)
        if (registeredEndPoints.size() == 0) endpointsByUserTenant.remove(userTenant, registeredEndPoints)
    }

    @Override
    void init(ExecutionContextFactory ecf) {
        this.ecf = ecf
    }

    @Override
    void destroy() {
        endpointsByUserTenant.clear()
        this.ecf = null
    }

    @Override
    void onMessage(NotificationMessage nm) {
        String messageWrapperJson = nm.getWrappedMessageJson()
        // notify by user, remember users notified
        Set<String> userIdsNotified = new HashSet<>()
        String tenantId = nm.tenantId
        for (String userId in nm.getUserIds()) {
            // add the user to those notified regardless of result, would be the same by group
            userIdsNotified.add(userId)
            sendMessageInternal(userId.concat(tenantId), nm, messageWrapperJson)
        }

        // notify by group, skipping users already notified
        if (nm.userGroupId) {
            EntityListIterator eli = ecf.getEntity(tenantId).find("moqui.security.UserGroupMember")
                    .conditionDate("fromDate", "thruDate", new Timestamp(System.currentTimeMillis()))
                    .condition("userGroupId", nm.userGroupId).iterator()
            EntityValue nextValue
            while ((nextValue = (EntityValue) eli.next()) != null) {
                String userId = (String) nextValue.userId
                if (userIdsNotified.contains(userId)) continue
                userIdsNotified.add(userId)
                sendMessageInternal(userId.concat(tenantId), nm, messageWrapperJson)
            }
        }
    }
    protected void sendMessageInternal(String userTenant, NotificationMessage nm, String messageWrapperJson) {
        ConcurrentHashMap<String, NotificationEndpoint> registeredEndPoints = endpointsByUserTenant.get(userTenant)
        if (registeredEndPoints == null) return
        for (NotificationEndpoint endpoint in registeredEndPoints.values()) {
            if (endpoint.session.isOpen() && endpoint.subscribedTopics.contains(nm.topic))
                endpoint.session.asyncRemote.sendText(messageWrapperJson)
        }
    }
}
