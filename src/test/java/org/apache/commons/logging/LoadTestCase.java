/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.logging;

import junit.framework.TestCase;

/**
 * testcase to emulate container and application isolated from container
 * @author  baliuka
 */
public class LoadTestCase extends TestCase{
    //TODO: need some way to add service provider packages
    static private String LOG_PCKG[] = {"org.apache.commons.logging",
                                        "org.apache.commons.logging.impl"};

    /**
     * A custom classloader which "duplicates" logging classes available
     * in the parent classloader into itself.
     * <p>
     * When asked to load a class that is in one of the LOG_PCKG packages,
     * it loads the class itself (child-first). This class doesn't need
     * to be set up with a classpath, as it simply uses the same classpath
     * as the classloader that loaded it.
     */
    static class AppClassLoader extends ClassLoader{

        java.util.Map classes = new java.util.HashMap();

        AppClassLoader(final ClassLoader parent){
            super(parent);
        }

        private Class def(final String name)throws ClassNotFoundException{

            Class result = (Class)classes.get(name);
            if(result != null){
                return result;
            }

            try{

                final ClassLoader cl = this.getClass().getClassLoader();
                final String classFileName = name.replace('.','/') + ".class";
                final java.io.InputStream is = cl.getResourceAsStream(classFileName);
                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

                while(is.available() > 0){
                    out.write(is.read());
                }

                final byte data [] = out.toByteArray();

                result = super.defineClass(name, data, 0, data.length );
                classes.put(name,result);

                return result;

            }catch(final java.io.IOException ioe){

                throw new ClassNotFoundException( name + " caused by "
                + ioe.getMessage() );
            }


        }

        // not very trivial to emulate we must implement "findClass",
        // but it will delegete to junit class loder first
        @Override
        public Class loadClass(final String name)throws ClassNotFoundException{

            //isolates all logging classes, application in the same classloader too.
            //filters exeptions to simlify handling in test
            for (final String element : LOG_PCKG) {
                if( name.startsWith( element ) &&
                name.indexOf("Exception") == -1   ){
                    return def(name);
                }
            }
            return super.loadClass(name);
        }

    }


    /**
     * Call the static setAllowFlawedContext method on the specified class
     * (expected to be a UserClass loaded via a custom classloader), passing
     * it the specified state parameter.
     */
    private void setAllowFlawedContext(final Class c, final String state) throws Exception {
        final Class[] params = {String.class};
        final java.lang.reflect.Method m = c.getDeclaredMethod("setAllowFlawedContext", params);
        m.invoke(null, state);
    }

    /**
     * Test what happens when we play various classloader tricks like those
     * that happen in web and j2ee containers.
     * <p>
     * Note that this test assumes that commons-logging.jar and log4j.jar
     * are available via the system classpath.
     */
    public void testInContainer()throws Exception{

        //problem can be in this step (broken app container or missconfiguration)
        //1.  Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
        //2.  Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
        // we expect this :
        // 1. Thread.currentThread().setContextClassLoader(appLoader);
        // 2. Thread.currentThread().setContextClassLoader(null);

        // Context classloader is same as class calling into log
        Class cls = reload();
        Thread.currentThread().setContextClassLoader(cls.getClassLoader());
        execute(cls);

        // Context classloader is the "bootclassloader". This is technically
        // bad, but LogFactoryImpl.ALLOW_FLAWED_CONTEXT defaults to true so
        // this test should pass.
        cls = reload();
        Thread.currentThread().setContextClassLoader(null);
        execute(cls);

        // Context classloader is the "bootclassloader". This is same as above
        // except that ALLOW_FLAWED_CONTEXT is set to false; an error should
        // now be reported.
        cls = reload();
        Thread.currentThread().setContextClassLoader(null);
        try {
            setAllowFlawedContext(cls, "false");
            execute(cls);
            fail("Logging config succeeded when context classloader was null!");
        } catch(final LogConfigurationException ex) {
            // expected; the boot classloader doesn't *have* JCL available
        }

        // Context classloader is the system classloader.
        //
        // This is expected to cause problems, as LogFactoryImpl will attempt
        // to use the system classloader to load the Log4JLogger class, which
        // will then be unable to cast that object to the Log interface loaded
        // via the child classloader. However as ALLOW_FLAWED_CONTEXT defaults
        // to true this test should pass.
        cls = reload();
        Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
        execute(cls);

        // Context classloader is the system classloader. This is the same
        // as above except that ALLOW_FLAWED_CONTEXT is set to false; an error
        // should now be reported.
        cls = reload();
        Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
        try {
            setAllowFlawedContext(cls, "false");
            execute(cls);
            fail("Error: somehow downcast a Logger loaded via system classloader"
                    + " to the Log interface loaded via a custom classloader");
        } catch(final LogConfigurationException ex) {
            // expected
        }
    }

    /**
     * Load class UserClass via a temporary classloader which is a child of
     * the classloader used to load this test class.
     */
    private Class reload()throws Exception{

        Class testObjCls = null;

        final AppClassLoader appLoader = new AppClassLoader(
                this.getClass().getClassLoader());
        try{

            testObjCls = appLoader.loadClass(UserClass.class.getName());

        }catch(final ClassNotFoundException cnfe){
            throw cnfe;
        }catch(final Throwable t){
            t.printStackTrace();
            fail("AppClassLoader failed ");
        }

        assertTrue( "app isolated" ,testObjCls.getClassLoader() == appLoader );


        return testObjCls;


    }


    private void execute(final Class cls)throws Exception{

            cls.newInstance();

    }

    @Override
    public void setUp() {
        // save state before test starts so we can restore it when test ends
        origContextClassLoader = Thread.currentThread().getContextClassLoader();
    }

    @Override
    public void tearDown() {
        // restore original state so a test can't stuff up later tests.
        Thread.currentThread().setContextClassLoader(origContextClassLoader);
    }

    private ClassLoader origContextClassLoader;
}
