package com.donutflipper.autotrader;

import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper to call DonutFlipper APIs via reflection, avoiding hard dependency.
 */
public class ReflectionHelper {
   private static final Logger LOGGER = LoggerFactory.getLogger("donutflipper-autotrader");
   
   public Object invokeStatic(String className, String methodName) throws Exception {
      return invokeStatic(className, methodName, new Class[]{}, new Object[]{});
   }
   
   public Object invokeStatic(String className, String methodName, Class<?>[] paramTypes, Object[] args) throws Exception {
      Class<?> clazz = Class.forName(className);
      Method method = clazz.getMethod(methodName, paramTypes);
      return method.invoke(null, args);
   }

   /** For package-private/private static methods (getMethod only finds public ones). */
   public Object invokeStaticDeclared(String className, String methodName, Class<?>[] paramTypes, Object[] args) throws Exception {
      Class<?> clazz = Class.forName(className);
      Method method = clazz.getDeclaredMethod(methodName, paramTypes);
      method.setAccessible(true);
      return method.invoke(null, args);
   }
   
   public Object invoke(Object obj, String methodName) throws Exception {
      return invoke(obj, methodName, new Class[]{}, new Object[]{});
   }
   
   public Object invoke(Object obj, String methodName, Class<?>[] paramTypes, Object[] args) throws Exception {
      if (obj == null) return null;
      Class<?> clazz = obj.getClass();
      Method method = findMethod(clazz, methodName, paramTypes);
      if (method == null) {
         throw new NoSuchMethodException(clazz.getName() + "." + methodName);
      }
      return method.invoke(obj, args);
   }
   
   private Method findMethod(Class<?> clazz, String name, Class<?>[] paramTypes) {
      try {
         return clazz.getMethod(name, paramTypes);
      } catch (NoSuchMethodException e) {
         if (clazz.getSuperclass() != null) {
            return findMethod(clazz.getSuperclass(), name, paramTypes);
         }
         return null;
      }
   }
}
