/*
 *
 *
 * Copyright (C) 2008 Pingtel Corp., certain elements licensed under a Contributor Agreement.
 * Contributors retain copyright to elements licensed under a Contributor Agreement.
 * Licensed to the User under the LGPL license.
 */
package org.sipfoundry.sipxconfig.freeswitch.config;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.velocity.VelocityContext;
import org.sipfoundry.sipxconfig.acccode.AuthCodes;
import org.sipfoundry.sipxconfig.advcallhandling.AdvancedCallHandling;
import org.sipfoundry.sipxconfig.callback.CallbackOnBusy;
import org.sipfoundry.sipxconfig.commserver.Location;
import org.sipfoundry.sipxconfig.conference.Bridge;
import org.sipfoundry.sipxconfig.conference.Conference;
import org.sipfoundry.sipxconfig.conference.ConferenceBridgeContext;
import org.sipfoundry.sipxconfig.domain.Domain;
import org.sipfoundry.sipxconfig.feature.FeatureManager;
import org.sipfoundry.sipxconfig.freeswitch.FreeswitchExtension;
import org.sipfoundry.sipxconfig.freeswitch.FreeswitchExtensionCollector;
import org.sipfoundry.sipxconfig.freeswitch.FreeswitchFeature;
import org.sipfoundry.sipxconfig.freeswitch.FreeswitchSettings;
import org.sipfoundry.sipxconfig.ivr.Ivr;
import org.sipfoundry.sipxconfig.parkorbit.ParkOrbitContext;
import org.springframework.beans.factory.annotation.Required;

/**
 * Generates default_context.xml.in
 */
public class DefaultContextConfiguration extends AbstractFreeswitchConfiguration {
    private ConferenceBridgeContext m_conferenceContext;
    private FreeswitchExtensionCollector m_freeswitchExtensionCollector;
    private FeatureManager m_featureManager;
    private ParkOrbitContext m_parkOrbitContext;
    private AdvancedCallHandling m_advancedCallHandling;
    private String m_fsEtcDir;

    @Override
    public void write(Writer writer, Location location, FreeswitchSettings settings) throws IOException {
        Bridge bridge = m_conferenceContext.getBridgeByServer(location.getFqdn());
        boolean authCodes = m_featureManager.isFeatureEnabled(AuthCodes.FEATURE, location);
        boolean callback = m_featureManager.isFeatureEnabled(CallbackOnBusy.FEATURE, location);
        boolean park = m_featureManager.isFeatureEnabled(ParkOrbitContext.FEATURE, location);
        List<FreeswitchExtension> extensions = m_freeswitchExtensionCollector.getExtensions();
        write(writer, location, bridge, authCodes, callback, park, m_parkOrbitContext.getParkOrbits(), extensions,
            settings.isBlindTransferEnabled(), settings.isSimplifyEnabled(), settings.getMaxForwards());
    }

    void write(Writer writer, Location location, Bridge bridge, boolean authCodes, boolean callback, boolean park,
            Collection orbits, List<FreeswitchExtension> extensions, boolean blindTransfer, boolean simplify,
            Integer maxForwards)
        throws IOException {
        VelocityContext context = new VelocityContext();
        if (bridge != null) {
            Set<Conference> conferences = bridge.getConferences();
            context.put("conferences", conferences);
        }
        if (authCodes) {
            context.put("acccode", true);
        }
        if (callback) {
            context.put("callback", true);
        }
        if (park) {
            context.put("park", true);
            context.put("orbits", orbits);
        }
        if (blindTransfer) {
            context.put("blindTransfer", true);
        }
        if (simplify) {
            context.put("simplify", true);
        }
        if (maxForwards != null) {
            context.put("maxForwards", maxForwards);
        }
        context.put("domainName", Domain.getDomain().getName());
        context.put("location", location);
        addAdditionalLocations(context, location);
        context.put("dollar", "$");
        context.put("conferenceExitScript", m_fsEtcDir + "conferenceExit.lua");
        context.put("advancedCallHandling",
            m_featureManager.
            isFeatureEnabled(FreeswitchFeature.FEATURE, location) ? m_advancedCallHandling.isEnabled() : false);
        context.put("proxyMediaThroughFS",
            m_featureManager.
            isFeatureEnabled(FreeswitchFeature.FEATURE, location) ? m_advancedCallHandling.isEnabledAnchor() : false);
        getFreeswitchExtensions(context, location, extensions);
        write(writer, context);
    }

    @Required
    public void setConferenceContext(ConferenceBridgeContext conferenceContext) {
        m_conferenceContext = conferenceContext;
    }

    @Required
    public void setFreeswitchExtensionCollector(FreeswitchExtensionCollector collector) {
        m_freeswitchExtensionCollector = collector;
    }

    @Override
    protected String getFileName() {
        return "dialplan/sipX_context.xml";
    }

    @Override
    protected String getTemplate() {
        return "freeswitch/default_context.xml.vm";
    }

    protected void getFreeswitchExtensions(VelocityContext context, Location location,
            List<FreeswitchExtension> extensions) {
        List<FreeswitchExtension> freeswitchExtensions = new ArrayList<FreeswitchExtension>();
        for (FreeswitchExtension extension : extensions) {
            if (extension.isEnabled()) {
                freeswitchExtensions.add(extension);
            }
        }
        context.put("freeswitchExtensions", freeswitchExtensions);
    }

    private void addAdditionalLocations(VelocityContext context, Location location) {
        List<Location> locations = getFeatureManager().getLocationsForEnabledFeature(Ivr.FEATURE);
        if (locations != null) {
            locations.remove(location);
            context.put("locations", locations);
        }
    }

    @Required
    public FeatureManager getFeatureManager() {
        return m_featureManager;
    }

    @Required
    public void setFeatureManager(FeatureManager featureManager) {
        m_featureManager = featureManager;
    }

    @Required
    public void setParkOrbitContext(ParkOrbitContext context) {
        m_parkOrbitContext = context;
    }

    public void setFreeswitchEtcDir(String directory) {
        m_fsEtcDir = directory;
    }

    @Required
    public void setAdvancedCallHandling(AdvancedCallHandling advancedCallHandling) {
        m_advancedCallHandling = advancedCallHandling;
    }
}
