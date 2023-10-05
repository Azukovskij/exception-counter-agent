package com.eisgroup.javaagent;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.JMException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.ObjectName;
import javax.management.ReflectionException;

/**
 * JMX bean that holds created exception count metrics
 * 
 * @author azukovskij
 *
 */
public class ThrowableCounterBean implements Consumer<Class<?>>, DynamicMBean {
    
    /**
     * property used in callback from system classloader
     */
    static final String SYSTEM_PROPERTY = "com.eisgroup.javaagent.ThrowableCounterBean";

    private static final ThrowableCounterBean INSTANCE = new ThrowableCounterBean();

    private static final String JMX_BEAN_NAME = "java.throwables:type=ThrowableCounterBean";
    
    private final Map<String, Long> counters = new ConcurrentHashMap<>();

    private final ThreadLocal<Boolean> recursion = new ThreadLocal<>();
    
    private final AtomicReference<MBeanInfo> mBeanInfo = new AtomicReference<>();

    /**
     * Registers counter bean into JMX and sets singleton for capturing {@link Throwable} instances
     * 
     * @throws IllegalStateException on JMX registration failure
     */
    public static void init()  {
        INSTANCE.registerJMX();
        System.getProperties().put(SYSTEM_PROPERTY, INSTANCE);
    }
    
    /**
     * Clears counter values
     */
    public void clear() {
        counters.clear();
    }

    /**
     * Accepts created exception to increment counter values
     * 
     * @param clazz exception type
     */
    @Override
    public void accept(Class<?> clazz) {
        if (!Boolean.TRUE.equals(recursion.get())) {
            runWithNoErrorCounter(() -> counters.compute(clazz.getName(), (k, count) -> {
                if (count == null) {
                    mBeanInfo.set(null); // clear cache
                    return 1L;
                }
                return count + 1;
            }));
        }
    }

    @Override
    public Object getAttribute(String attribute)
            throws AttributeNotFoundException, MBeanException, ReflectionException {
        return counters.get(attribute);
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return mBeanInfo.accumulateAndGet(null, (prev,next) -> prev == null
            ? runWithNoErrorCounter(() -> buildDynamicMBeanInfo())
            : prev);
    }

    @Override
    public void setAttribute(Attribute attribute)
        throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException {
    }

    @Override
    public AttributeList getAttributes(String[] attributes) {
        return null;
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        return null;
    }

    @Override
    public Object invoke(String actionName, Object[] params, String[] signature)
        throws MBeanException, ReflectionException {
        if ("clear".equals(actionName)) {
            clear();
        }
        return null;
    }
    
    private void registerJMX() throws IllegalStateException {
        runWithNoErrorCounter(() -> {
            try {
                return ManagementFactory.getPlatformMBeanServer()
                    .registerMBean(this, new ObjectName(JMX_BEAN_NAME));
            } catch (JMException e) {
                throw new IllegalStateException("Failed to regsiter JMX", e);
            }
        });
    }
    
    private MBeanInfo buildDynamicMBeanInfo() {
        try {
            var attrs = counters.entrySet().stream()
                .map(e -> new MBeanAttributeInfo(e.getKey(), 
                        Long.class.getSimpleName(),
                        "Error count", true, false, false))
                .toArray(MBeanAttributeInfo[]::new);
            var op = new MBeanOperationInfo("clear", INSTANCE.getClass().getMethod("clear"));
            return new MBeanInfo(getClass().getName(), null, attrs, null, 
                    new MBeanOperationInfo[] { op },
                    new MBeanNotificationInfo[0]);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
    
    private <T> T runWithNoErrorCounter(Supplier<T> action) {
        recursion.set(true);
        try {
            return action.get();
        } finally {
            recursion.remove();
        }
    }
    
}
