package com.eisgroup.javaagent;

import static com.eisgroup.javaagent.ThrowableCounterBean.SYSTEM_PROPERTY;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;

/**
 * Java agent that instruments {@link Throwable#fillInStackTrace()} invoked from exception constructor to populate {@link ThrowableCounterBean} metrics
 * 
 * @author azukovskij
 *
 */
public class ThrowableMetricsJavaAgent {
    
    public static final Map<String, Long> COUNTERS = new ConcurrentHashMap<>();

    public static void attach() {
        agentmain(null, ByteBuddyAgent.install());
    }
    @SuppressWarnings("UnusedDeclaration")
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        instrument(agentArgs, instrumentation);   
    }

    @SuppressWarnings("UnusedDeclaration")
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        instrument(agentArgs, instrumentation);   
    }
    
    private static void instrument(String agentArgs, Instrumentation instrumentation) {
        try {
            var byteBuddy = new ByteBuddy();
            var factory = Class.forName("java.lang.Throwable");
            byteBuddy.redefine(factory)
                .visit(Advice
                    .withCustomMapping()
                    .to(Interceptor.class)
                        .on(named("fillInStackTrace").and(takesArguments(0))))
                .make()
                .load(factory.getClassLoader(), ClassReloadingStrategy.of(instrumentation));

            // Register JMX bean
            ThrowableCounterBean.init();
        } catch (Exception ignored) {
            return;
        }
    }
    
    /**
     * Notifies {@link ThrowableCounterBean} on exception creation 
     * 
     * @author azukovskij
     *
     */
    public static class Interceptor {
        
        /**
         * Intercepts exception create action
         * 
         * @param caller exception
         */
        @Advice.OnMethodExit(suppress = Throwable.class)
        @SuppressWarnings({ "rawtypes", "unchecked" })
        public static void intercept(@Advice.This Object caller) {
            var instance = System.getProperties().get(SYSTEM_PROPERTY);
            if (instance instanceof Consumer) {
                ((Consumer)instance).accept(caller.getClass());
            }
        }
        
    }
    
}