/*
 *
 *
 * Copyright (C) 2009 Pingtel Corp., certain elements licensed under a Contributor Agreement.
 * Contributors retain copyright to elements licensed under a Contributor Agreement.
 * Licensed to the User under the LGPL license.
 *
 */

package org.sipfoundry.sipxconfig.branch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Criteria;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Restrictions;
import org.sipfoundry.sipxconfig.common.CoreContext;
import org.sipfoundry.sipxconfig.common.DaoUtils;
import org.sipfoundry.sipxconfig.common.Replicable;
import org.sipfoundry.sipxconfig.common.SipxHibernateDaoSupport;
import org.sipfoundry.sipxconfig.common.User;
import org.sipfoundry.sipxconfig.common.UserException;
import org.sipfoundry.sipxconfig.common.event.DaoEventListener;
import org.sipfoundry.sipxconfig.commserver.imdb.ReplicationManager;
import org.sipfoundry.sipxconfig.setting.Group;
import org.sipfoundry.sipxconfig.setup.SetupListener;
import org.sipfoundry.sipxconfig.setup.SetupManager;
import org.sipfoundry.sipxconfig.time.NtpManager;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.hibernate3.HibernateCallback;

public class BranchManagerImpl extends SipxHibernateDaoSupport<Branch>
        implements BranchManager, SetupListener, DaoEventListener {
    private static final Log LOG = LogFactory.getLog(BranchManagerImpl.class);
    private static final String NAME_PROP_NAME = "name";
    private static final String UPDATE_BRANCH_TIMEZONE = "update_branch_tz";

    private JdbcTemplate m_jdbcTemplate;
    private NtpManager m_ntpManager;
    private ReplicationManager m_replicationManager;
    private CoreContext m_coreContext;

    @Override
    public Branch getBranch(Integer branchId) {
        return load(Branch.class, branchId);
    }

    @Override
    public Branch retrieveBranch(Integer branchId) {
        return getHibernateTemplate().get(Branch.class, branchId);
    }

    @Override
    public Branch getBranch(String branchName) {
        return loadBranchByUniqueProperty(NAME_PROP_NAME, branchName);
    }

    @Override
    public void saveBranch(Branch branch) {
        // Check for duplicate names before saving the branch
        if (branch.isNew() || (!branch.isNew() && isNameChanged(branch))) {
            checkForDuplicateName(branch);
        }
        if (!branch.isNew()) {
            getHibernateTemplate().merge(branch);
        } else {
            getHibernateTemplate().save(branch);
        }
    }

    private boolean isNameChanged(Branch branch) {
        return !getBranch(branch.getId()).getName().equals(branch.getName());
    }

    private void checkForDuplicateName(Branch branch) {
        String branchName = branch.getName();
        Branch existingBranch = getBranch(branchName);
        if (existingBranch != null) {
            throw new UserException("&duplicate.branchName.error", branchName);
        }
    }

    /*
     * (non-Javadoc) Use plain sql for increased efficiency when deleting user groups. A thing to
     * note is that this method breaks the convention established by DaoEventDispatcher, namely
     * publish delete event first, then proceed with the actual delete. It will actually manually
     * delete from DB the group associations and the group and then the delete event is published.
     * In the case of groups now, only ReplicationTrigger will trigger the delete sequence, all
     * other event listeners that listened to group deletes were removed, and control moved in
     * this method. This was the price to pay for increased efficiency in saving large groups.
     */
    @Override
    public void deleteBranches(Collection<Integer> branchIds) {
        try {
            List<String> sqlUpdates = new ArrayList<String>();
            Collection<Branch> branches = new ArrayList<Branch>(branchIds.size());
            Set<User> affectedUsers = new HashSet<User>();
            for (Integer id : branchIds) {
                Branch branch = getBranch(id);
                Collection<User> branchUsers = m_coreContext.getUsersForBranch(branch);
                affectedUsers.addAll(branchUsers);
                branches.add(branch);
                sqlUpdates.add("update users set branch_id=null where branch_id=" + id);
                sqlUpdates.add("update group_storage set branch_id=null where branch_id=" + id);
                sqlUpdates.add("delete from branch_route_domain where branch_id=" + id);
                sqlUpdates.add("delete from branch_route_subnet where branch_id=" + id);
                sqlUpdates.add("delete from branch_branch where branch_id=" + id);
                sqlUpdates.add("delete from branch_branch where associated_branch_id=" + id);
                sqlUpdates.add("delete from branch where branch_id=" + id);
                getHibernateTemplate().evict(branch);
            }
            if (!sqlUpdates.isEmpty()) {
                m_jdbcTemplate.batchUpdate(sqlUpdates.toArray(new String[sqlUpdates.size()]));
                for (Branch branch : branches) {
                    getDaoEventPublisher().publishDelete(branch);
                }
                for (User user : affectedUsers) {
                    // need to reload and replicate the affected users
                    getHibernateTemplate().refresh(user);
                    for (Group group : user.getGroups()) {
                        getHibernateTemplate().refresh(group);
                    }
                    m_replicationManager.replicateEntity(user);
                }
            }
        } catch (Exception ex) {
            throw new UserException("&branches.delete.err");
        }

    }

    @Override
    public List<Branch> getBranches() {
        List<Branch> branches = getHibernateTemplate().loadAll(Branch.class);
        return branches;
    }

    private Branch loadBranchByUniqueProperty(String propName, String propValue) {
        final Criterion expression = Restrictions.eq(propName, propValue);

        HibernateCallback callback = new HibernateCallback() {
            @Override
            public Object doInHibernate(Session session) {
                Criteria criteria = session.createCriteria(Branch.class).add(expression);
                return criteria.list();
            }
        };

        List branches = getHibernateTemplate().executeFind(callback);
        Branch branch = (Branch) DaoUtils.requireOneOrZero(branches, expression.toString());

        return branch;
    }

    @Override
    public List<Branch> loadBranchesByPage(final int firstRow, final int pageSize, final String[] orderBy,
            final boolean orderAscending) {
        return loadBeansByPage(Branch.class, null, null, firstRow, pageSize, orderBy, orderAscending);
    }

    @Override
    public List<?> getFeatureNames(Integer branchId, String sqlQuery, Class<?> c) {
        Query q = getHibernateTemplate().getSessionFactory().getCurrentSession().createSQLQuery(sqlQuery).addEntity(c);
        q.setInteger("branchId", branchId);
        List<?> names = q.list();
        return names;
    }

    @Override
    public List< ? > getFeatureNames(String sqlQuery, Class< ? > c) {
        Query q = getHibernateTemplate().getSessionFactory().getCurrentSession().createSQLQuery(sqlQuery).addEntity(c);
        List<?> names = q.list();
        return names;
    }

    @Override
    public void clear() {
        removeAll(Branch.class);
    }

    @Override
    public boolean setup(SetupManager manager) {
        LOG.debug("Entering branch update setup task: " + manager.isFalse(UPDATE_BRANCH_TIMEZONE));
        if (manager.isFalse(UPDATE_BRANCH_TIMEZONE)) {
            LOG.info("Running branch update task: setting branch timezone to server timezone.");
            String systemTz = m_ntpManager.getSystemTimezone();
            for (Branch branch : getBranches()) {
                String timezone = branch.getTimeZone();
                if (StringUtils.isEmpty(timezone) || "Africa/Abidjan".equals(timezone)) {
                    LOG.debug(String.format("Setting branch %s to %s", branch.getName(), systemTz));
                    branch.setTimeZone(systemTz);
                    saveBranch(branch);
                }
            }
            manager.setTrue(UPDATE_BRANCH_TIMEZONE);
        }
        return true;
    }

    public void setConfigJdbcTemplate(JdbcTemplate jdbcTemplate) {
        m_jdbcTemplate = jdbcTemplate;
    }

    public void setNtpManager(NtpManager ntpManager) {
        m_ntpManager = ntpManager;
    }

    @Override
    public List<Replicable> getReplicables() {
        List<Replicable> replicables = new ArrayList<Replicable>();
        replicables.addAll(getBranches());
        return replicables;
    }

    @Required
    public void setReplicationManager(ReplicationManager replicationManager) {
        m_replicationManager = replicationManager;
    }

    @Required
    public void setCoreContext(CoreContext coreContext) {
        m_coreContext = coreContext;
    }

    @Override
    public void onDelete(Object entity) {
    }

    @Override
    public void onSave(Object entity) {
        if (entity instanceof Branch) {
            Branch branch = (Branch) entity;
            Collection<User> users = m_coreContext.getUsersForBranch(branch);
            for (User user : users) {
                m_replicationManager.replicateEntity(user);
            }
        }
    }
}
