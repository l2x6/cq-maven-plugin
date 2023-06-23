/*
 * Copyright (c) 2020 CQ Maven Plugin
 * project contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.l2x6.cq;

import java.util.List;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.MavenExecutionRequest;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;

/**
 * An event spy that effectively replaces {@code -Pfastinstall} with {@code -Dquickly}
 */
@Component(role = EventSpy.class)
public class AliasFastinstallEventSpy extends AbstractEventSpy {
    @Requirement
    private Logger logger;

    @Override
    public void init(Context context) throws Exception {
        super.init(context);
        //logger.info("AliasFastinstallEventSpy is registered");
    }

    public void onEvent(Object event) throws Exception {
        if (event instanceof MavenExecutionRequest) {
            final MavenExecutionRequest request = (MavenExecutionRequest) event;
            final List<String> activeProfiles = request.getActiveProfiles();
            if (activeProfiles.contains("fastinstall")) {
                logger.info("-Pfastinstall replaced by -Dquickly via AliasFastinstallEventSpy");
                activeProfiles.remove("full");
                request.getInactiveProfiles().add("full");
            }
        }
    }

}
