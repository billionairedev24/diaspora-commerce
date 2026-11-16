package com.afrotransact.platform.web.module;

import java.util.Map;

import com.afrotransact.module.ModuleMode;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** The condition behind {@link ConditionalOnModuleMode}. */
final class OnModuleModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnModuleMode.class.getName());
        if (attributes == null) {
            // a scan that reaches the annotation type itself (it carries @Conditional as a meta-annotation) is not an edge
            return true;
        }
        String caller = (String) attributes.get("caller");
        String callee = (String) attributes.get("callee");
        ModuleMode wanted = (ModuleMode) attributes.get("value");
        return effective(context.getEnvironment(), caller, callee) == wanted;
    }

    /** The mode the edge really runs in; see {@link ConditionalOnModuleMode}. */
    static ModuleMode effective(Environment env, String caller, String callee) {
        String prefix = "at." + caller.replace("-", "");
        String edge = env.getProperty(prefix + "." + callee.replace("-", "") + ".mode");
        if (edge != null && !edge.isBlank()) {
            return ModuleMode.parse(edge, ModuleMode.HTTP);
        }
        ModuleMode general = ModuleMode.parse(env.getProperty(prefix + ".mode"), ModuleMode.HTTP);
        boolean calleeOff = "false".equalsIgnoreCase(env.getProperty("at.modules." + callee + ".enabled"));
        return general == ModuleMode.IN_PROCESS && calleeOff ? ModuleMode.HTTP : general;
    }
}
