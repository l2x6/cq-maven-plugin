package org.apache.camel.quarkus.component.foo.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.pkg.steps.NativeBuild;
import org.apache.camel.quarkus.core.JvmOnlyRecorder;
import org.jboss.logging.Logger;

public class FooProcessor {

    private static final Logger LOG = Logger.getLogger(IrcProcessor.class);
    private static final String FEATURE = "camel-foo";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

}
