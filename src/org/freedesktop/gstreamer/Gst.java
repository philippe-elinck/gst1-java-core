/* 
 * Copyright (c) 2019 Neil C Smith
 * Copyright (c) 2018 Antonio Morales
 * Copyright (c) 2007 Wayne Meissner
 * 
 * This file is part of gstreamer-java.
 *
 * This code is free software: you can redistribute it and/or modify it under 
 * the terms of the GNU Lesser General Public License version 3 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT 
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or 
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License 
 * version 3 for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * version 3 along with this work.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.freedesktop.gstreamer;

import org.freedesktop.gstreamer.webrtc.WebRTCSessionDescription;
import org.freedesktop.gstreamer.query.Query;
import org.freedesktop.gstreamer.message.Message;
import org.freedesktop.gstreamer.event.Event;
import org.freedesktop.gstreamer.glib.GSocket;
import org.freedesktop.gstreamer.glib.GInetSocketAddress;
import org.freedesktop.gstreamer.glib.GError;
import static org.freedesktop.gstreamer.lowlevel.GstAPI.GST_API;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.elements.BaseSink;
import org.freedesktop.gstreamer.elements.BaseSrc;
import org.freedesktop.gstreamer.elements.BaseTransform;
import org.freedesktop.gstreamer.elements.DecodeBin;
import org.freedesktop.gstreamer.elements.PlayBin;
import org.freedesktop.gstreamer.elements.URIDecodeBin;
import org.freedesktop.gstreamer.webrtc.WebRTCBin;
import org.freedesktop.gstreamer.glib.GDate;
import org.freedesktop.gstreamer.glib.GInetAddress;
import org.freedesktop.gstreamer.glib.GSocketAddress;
import org.freedesktop.gstreamer.glib.MainContextExecutorService;
import org.freedesktop.gstreamer.lowlevel.GMainContext;
import org.freedesktop.gstreamer.lowlevel.GstAPI.GErrorStruct;
import org.freedesktop.gstreamer.lowlevel.GstTypes;
import org.freedesktop.gstreamer.glib.NativeObject;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.logging.Level;
import static org.freedesktop.gstreamer.lowlevel.GstParseAPI.GSTPARSE_API;

/**
 * Media library supporting arbitrary formats and filter graphs.
 *
 */
@SuppressWarnings("deprecation")
public final class Gst {

    private final static Logger LOG = Logger.getLogger(Gst.class.getName());
    private final static AtomicInteger INIT_COUNT = new AtomicInteger(0);
    private final static boolean CHECK_VERSIONS = !Boolean.getBoolean("gstreamer.suppressVersionChecks");

    private static ScheduledExecutorService executorService;
    private static volatile CountDownLatch quit = new CountDownLatch(1);
    private static GMainContext mainContext;
    private static boolean useDefaultContext = false;
    private static List<Runnable> shutdownTasks = Collections.synchronizedList(new ArrayList<Runnable>());
    // set minorVersion to a value guaranteed to be >= anything else unless set in init()
    private static int minorVersion = Integer.MAX_VALUE;

    public static class NativeArgs {

        public IntByReference argcRef;
        public PointerByReference argvRef;
        Memory[] argsCopy;
        Memory argvMemory;

        public NativeArgs(String progname, String[] args) {
            //
            // Allocate some native memory to pass the args down to the native layer
            //
            argsCopy = new Memory[args.length + 2];
            argvMemory = new Memory(argsCopy.length * Native.POINTER_SIZE);

            //
            // Insert the program name as argv[0]
            //
            Memory arg = new Memory(progname.getBytes().length + 4);
            arg.setString(0, progname);
            argsCopy[0] = arg;

            for (int i = 0; i < args.length; i++) {
                arg = new Memory(args[i].getBytes().length + 1);
                arg.setString(0, args[i]);
                argsCopy[i + 1] = arg;
            }
            argvMemory.write(0, argsCopy, 0, argsCopy.length);
            argvRef = new PointerByReference(argvMemory);
            argcRef = new IntByReference(args.length + 1);
        }

        String[] toStringArray() {
            //
            // Unpack the native arguments back into a String array
            //
            List<String> args = new ArrayList<String>();
            Pointer argv = argvRef.getValue();
            for (int i = 1; i < argcRef.getValue(); i++) {
                Pointer arg = argv.getPointer(i * Native.POINTER_SIZE);
                if (arg != null) {
                    args.add(arg.getString(0));
                }
            }
            return args.toArray(new String[args.size()]);
        }
    }

    /**
     * Creates a new instance of Gst
     */
    private Gst() {
    }

    /**
     * Gets the version of gstreamer currently available. This function can be
     * used prior to calling {@link #init(org.freedesktop.gstreamer.Version) }
     *
     * @return the version of gstreamer
     */
    public static Version getVersion() {
        long[] major = {0}, minor = {0}, micro = {0}, nano = {0};
        GST_API.gst_version(major, minor, micro, nano);
        return new Version((int) major[0], (int) minor[0], (int) micro[0], (int) nano[0]);
    }

    /**
     * Gets the the version of gstreamer currently in use, as a String.
     *
     * @return a string representation of the version.
     */
    public static String getVersionString() {
        return GST_API.gst_version_string();
    }

    /**
     * Some functions in the GStreamer core might install a custom SIGSEGV
     * handler to better catch and report errors to the application. Currently
     * this feature is enabled by default when loading plugins.
     * <p>
     * Applications might want to disable this behaviour with the
     * {@link #setSegTrap(boolean) } function. This is typically done if the
     * application wants to install its own handler without GStreamer
     * interfering.
     *
     * @return Segmentation Trap status.
     */
    public static boolean isSegTrapEnabled() {
        return GST_API.gst_segtrap_is_enabled();
    }

    /**
     * Applications might want to disable/enable the SIGSEGV handling of the
     * GStreamer core. See {@link #isSegTrapEnabled() } for more information.
     *
     * @param enabled
     */
    public static void setSegTrap(boolean enabled) {
        GST_API.gst_segtrap_set_enabled(enabled);
    }

    /**
     * Test whether the GStreamer library already initialized.
     *
     * @return true if the GStreamer library already initialized.
     */
    public static synchronized final boolean isInitialized() {
        return INIT_COUNT.get() > 0;
    }

    /**
     * Gets the common {@link ScheduledExecutorService} used to execute
     * background tasks and schedule timeouts.
     *
     * @return an executor that can be used for background tasks.
     */
    public static ScheduledExecutorService getExecutor() {
        return getScheduledExecutorService();
    }

    /**
     * Gets the common {@code ExecutorService} used to execute background tasks.
     *
     * @return an executor that can be used for background tasks.
     */
    @Deprecated
    public static ExecutorService getExecutorService() {
        return getScheduledExecutorService();
    }

    /**
     * Gets the common {@code ScheduledExecutorService} used to execute
     * background tasks and schedule timeouts.
     *
     * @return an executor that can be used for background tasks.
     */
    @Deprecated
    public static ScheduledExecutorService getScheduledExecutorService() {
        return executorService;
    }

    /**
     * Signals the thread that called {@link #init} to return.
     */
    public static void quit() {
        quit.countDown();
    }

    /**
     * Create a new pipeline based on command line syntax.
     *
     * Please note that you might get a return value that is not NULL even
     * though the error is set. In this case there was a recoverable parsing
     * error and you can try to play the pipeline.
     *
     * @param pipelineDescription the command line describing the pipeline
     * @param errors a list that any errors will be added to
     * @return a new element on success. If more than one top-level element is
     * specified by the pipeline_description , all elements are put into a
     * Pipeline, which then is returned.
     * @throws GstException if the pipeline / element could not be created
     */
    public static Element parseLaunch(String pipelineDescription, List<GError> errors) {
        Pointer[] err = {null};
        Element pipeline = GSTPARSE_API.gst_parse_launch(pipelineDescription, err);
        if (pipeline == null) {
            throw new GstException(new GError(new GErrorStruct(err[0])));
        }

        // check for error
        if (err[0] != null) {
            if (errors != null) {
                errors.add(new GError(new GErrorStruct(err[0])));
            } else {
                LOG.log(Level.WARNING, new GError(new GErrorStruct(err[0])).getMessage());
            }
        }

        return pipeline;
    }

    /**
     * Create a new pipeline based on command line syntax.
     *
     * Please note that you might get a return value that is not NULL even
     * though the error is set. In this case there was a recoverable parsing
     * error and you can try to play the pipeline.
     *
     * @param pipelineDescription the command line describing the pipeline
     * @return a new element on success. If more than one top-level element is
     * specified by the pipeline_description , all elements are put into a
     * Pipeline, which then is returned.
     * @throws GstException if the pipeline / element could not be created
     */
    public static Element parseLaunch(String pipelineDescription) {
        return parseLaunch(pipelineDescription, null);
    }

    /**
     * Create a new element based on command line syntax.
     *
     * error will contain an error message if an erroneous pipeline is
     * specified. An error does not mean that the pipeline could not be
     * constructed.
     *
     * @param pipelineDescription An array of strings containing the command
     * line describing the pipeline.
     * @param errors a list that any errors will be added to
     * @return a new element on success.
     * @throws GstException if the pipeline / element could not be created
     */
    public static Element parseLaunch(String[] pipelineDescription, List<GError> errors) {
        Pointer[] err = {null};
        Element pipeline = GSTPARSE_API.gst_parse_launchv(pipelineDescription, err);
        if (pipeline == null) {
            throw new GstException(new GError(new GErrorStruct(err[0])));
        }

        // check for error
        if (err[0] != null) {
            if (errors != null) {
                errors.add(new GError(new GErrorStruct(err[0])));
            } else {
                LOG.log(Level.WARNING, new GError(new GErrorStruct(err[0])).getMessage());
            }
        }

        return pipeline;
    }

    /**
     * Create a new element based on command line syntax.
     *
     * error will contain an error message if an erroneous pipeline is
     * specified. An error does not mean that the pipeline could not be
     * constructed.
     *
     * @param pipelineDescription An array of strings containing the command
     * line describing the pipeline.
     * @return a new element on success.
     * @throws GstException if the pipeline / element could not be created
     */
    public static Element parseLaunch(String[] pipelineDescription) {
        return parseLaunch(pipelineDescription, null);
    }

    /**
     * Creates a bin from a text bin description.
     *
     * This function allows creation of a bin based on the syntax used in the
     * gst-launch utillity.
     *
     * @param binDescription the command line describing the bin
     * @param ghostUnlinkedPads whether to create ghost pads for the bin from
     * any unlinked pads
     * @param errors list that any errors will be added to
     * @return The new Bin.
     * @throws GstException if the bin could not be created
     */
    public static Bin parseBinFromDescription(String binDescription, boolean ghostUnlinkedPads, List<GError> errors) {

        Pointer[] err = {null};
        Bin bin = GSTPARSE_API.gst_parse_bin_from_description(binDescription, ghostUnlinkedPads, err);

        if (bin == null) {
            throw new GstException(new GError(new GErrorStruct(err[0])));
        }

        // check for error
        if (err[0] != null) {
            if (errors != null) {
                errors.add(new GError(new GErrorStruct(err[0])));
            } else {
                LOG.log(Level.WARNING, new GError(new GErrorStruct(err[0])).getMessage());
            }
        }

        return bin;
    }

    /**
     * Creates a bin from a text bin description.
     *
     * This function allows creation of a bin based on the syntax used in the
     * gst-launch utillity.
     *
     * @param binDescription the command line describing the bin
     * @param ghostUnlinkedPads whether to create ghost pads for the bin from
     * any unlinked pads
     * @return The new Bin.
     * @throws GstException if the bin could not be created
     */
    public static Bin parseBinFromDescription(String binDescription, boolean ghostUnlinkedPads) {
        return parseBinFromDescription(binDescription, ghostUnlinkedPads, null);
    }

    /**
     * Waits for the gstreamer system to shutdown via a call to {@link #quit}.
     * <p>
     * For most gui programs, this is of little use. However, it can be a
     * convenient way of keeping the main thread alive whilst gstreamer
     * processing on other threads continues.
     */
    public static void main() {
        try {
            CountDownLatch latch = quit;
            if (latch != null) {
                latch.await();
            }
        } catch (InterruptedException ex) {
        } finally {
            quit = new CountDownLatch(1);
        }
    }

    /**
     * Schedules a task for execution on the gstreamer background
     * {@link java.util.concurrent.Executor}.
     *
     * @param task the task to execute.
     */
    public static void invokeLater(final Runnable task) {
        getExecutor().execute(task);
    }

    /**
     * Executes a task on the gstreamer background
     * {@link java.util.concurrent.Executor}, waiting until the task completes
     * before returning.
     *
     * @param task the task to execute.
     */
    @Deprecated
    public static void invokeAndWait(Runnable task) {
        try {
            getExecutorService().submit(task).get();
        } catch (Exception ex) {
            throw new RuntimeException(ex.getCause());
        }
    }

    /**
     * Gets the current main context used (if any).
     *
     * @return a main context.
     */
    // @TODO leaking lowlevel
    public static GMainContext getMainContext() {
        return mainContext;
    }

    /**
     * Initializes the GStreamer library.
     * <p>
     * This is a shortcut if no arguments are to be passed to gstreamer.
     * <p>
     * <b>This is equivalent to calling
     * {@link #init(org.freedesktop.gstreamer.Version) } with
     * {@link Version#BASELINE}, currently GStreamer 1.8.</b> If you require
     * features from a later version of GStreamer you should specify the
     * required version.
     *
     * @throws org.freedesktop.gstreamer.GstException
     */
    public static final void init() throws GstException {
        init(Version.BASELINE, "gst1-java-core");
    }

    /**
     * Initializes the GStreamer library.
     * <p>
     * This is a shortcut if no arguments are to be passed to gstreamer.
     *
     * @param requiredVersion
     * @throws org.freedesktop.gstreamer.GstException
     */
    public static final void init(Version requiredVersion) throws GstException {
        init(requiredVersion, "gst1-java-core");
    }

    /**
     * Initializes the GStreamer library.
     * <p>
     * This sets up internal path lists, registers built-in elements, and loads
     * standard plugins.
     * <p>
     * <b>This is equivalent to calling
     * {@link #init(org.freedesktop.gstreamer.Version, java.lang.String, java.lang.String...) }
     * with {@link Version#BASELINE}, currently GStreamer 1.8.</b> If you
     * require features from a later version of GStreamer you should specify the
     * required version.
     *
     * @param progname the java program name.
     * @param args the java argument list.
     * @return the list of arguments with any gstreamer specific options
     * stripped out.
     * @throws org.freedesktop.gstreamer.GstException
     */
    public static synchronized final String[] init(String progname, String... args) throws GstException {
        return init(Version.BASELINE, progname, args);
    }

    /**
     * Initializes the GStreamer library.
     * <p>
     * This sets up internal path lists, registers built-in elements, and loads
     * standard plugins.
     *
     * @param requestedVersion the minimum requested GStreamer version.
     * @param progname the java program name.
     * @param args the java argument list.
     * @return the list of arguments with any gstreamer specific options
     * stripped out.
     * @throws org.freedesktop.gstreamer.GstException
     */
    public static synchronized final String[] init(Version requestedVersion,
            String progname, String... args) throws GstException {

        if (CHECK_VERSIONS) {
            Version availableVersion = getVersion();
            if (requestedVersion.getMajor() != 1 || availableVersion.getMajor() != 1) {
                throw new GstException("gst1-java-core only supports GStreamer 1.x");
            }
            if (requestedVersion.getMinor() < 8) {
                requestedVersion = new Version(1, 8);
            }
            if (!availableVersion.checkSatisfies(requestedVersion)) {
                throw new GstException(String.format(
                        "The requested version of GStreamer is not available\nRequested : %s\nAvailable : %s\n",
                        requestedVersion, availableVersion));
            }
        }

        //
        // Only do real init the first time through
        //
        if (INIT_COUNT.getAndIncrement() > 0) {
            if (CHECK_VERSIONS) {
                if (requestedVersion.getMinor() > minorVersion) {
                    minorVersion = (int) requestedVersion.getMinor();
                }
            }
            return args;
        }

        NativeArgs argv = new NativeArgs(progname, args);

        Pointer[] error = {null};
        if (!GST_API.gst_init_check(argv.argcRef, argv.argvRef, error)) {
            INIT_COUNT.decrementAndGet();
            throw new GstException(new GError(new GErrorStruct(error[0])));
        }

        LOG.fine("after gst_init, argc=" + argv.argcRef.getValue());

        Version runningVersion = getVersion();
        if (runningVersion.getMajor() != 1) {
            LOG.warning("gst1-java-core only supports GStreamer 1.x");
        }

        if (useDefaultContext) {
            mainContext = GMainContext.getDefaultContext();
            executorService = new MainContextExecutorService(mainContext);
        } else {
            mainContext = new GMainContext();
            executorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
        }
        quit = new CountDownLatch(1);
        loadAllClasses();

        if (CHECK_VERSIONS) {
            minorVersion = requestedVersion.getMinor();
        }

        return argv.toStringArray();
    }

    /**
     * Clean up any resources created by GStreamer in
     * {@link #init(org.freedesktop.gstreamer.Version)}.
     *
     * <b>It is normally not needed to call this function in a normal
     * application as the resources will automatically be freed when the program
     * terminates. This function is therefore mostly used by testsuites and
     * other memory profiling tools.</b>
     *
     * After this call GStreamer (including this method) should not be used
     * anymore.
     */
    public static synchronized final void deinit() {
        //
        // Only perform real shutdown if called as many times as Gst.init() is
        //
        if (INIT_COUNT.decrementAndGet() > 0) {
            return;
        }
        // Perform any cleanup tasks
        for (Object task : shutdownTasks.toArray()) {
            ((Runnable) task).run();
        }

        // Stop any more tasks/timers from being scheduled
        executorService.shutdown();

        // Wake up the run thread.
        quit();

        // Wait for tasks to complete.
        try {
            if (!executorService.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                // Force-kill everything
                executorService.shutdownNow();
            }
        } catch (InterruptedException ex) {
        }

        mainContext = null;
        System.gc(); // Make sure any dangling objects are unreffed before calling deinit().
        GST_API.gst_deinit();
    }

    /**
     * Adds a task to be called when {@link Gst#deinit} is called.
     * <p>
     * This is used internally, and is not recommended for other uses.
     *
     * @param task the task to execute.
     */
    public static void addStaticShutdownTask(Runnable task) {
        shutdownTasks.add(task);
    }

    /**
     * Instructs gstreamer-java to use the default main context.
     * <p>
     * This may be useful if integration with the GTK main loop is desirable, as
     * all {@link Bus} signals and timers will be executed in the context of the
     * GTK main loop.
     * <p>
     * For the majority of programs though, it is better to wrap the individual
     * listeners in a proxy which executes the listener in the appropriate
     * context.
     *
     * @param useDefault if true, use the default glib main context.
     */
    public static void setUseDefaultContext(boolean useDefault) {
        useDefaultContext = useDefault;
    }

    /**
     * Checks that the version of GStreamer requested in init() satisfies the
     * given version or throws an exception.
     *
     * @param major major version, only 1 is supported
     * @param minor minor version required
     * @throws GstException if the requested version support was not requested
     */
    public static void checkVersion(int major, int minor) {
        if (CHECK_VERSIONS && (major != 1 || minor > minorVersion)) {
            throw new GstException("Not supported by requested GStreamer version");
        }
    }

    /**
     * Tests that the version of GStreamer requested in init() satisfies the
     * given version.
     *
     * @param major major version, only 1 is supported
     * @param minor minor version required
     * @return boolean whether the version requirement can be satisfied
     */
    public static boolean testVersion(int major, int minor) {
        if (CHECK_VERSIONS && (major != 1 || minor > minorVersion)) {
            return false;
        }
        return true;
    }

    // Make the gstreamer executor threads daemon, so they don't stop the main 
    // program from exiting
    private static final ThreadFactory threadFactory = new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(0);

        /**
         * Determines if Gst has been started from an applet and returns it's
         * parent group.
         *
         * This is to avoid a problem where the service thread is killed when an
         * applet is destroyed. If multiple applets are active simultaneously,
         * this could be a problem.
         *
         * @return Applet's parent ("main") thread group or null, if not running
         * inside an applet
         */
        private ThreadGroup getThreadGroup() {
            ThreadGroup tg = Thread.currentThread().getThreadGroup();
            try {
                Class<?> atgClass = Class.forName("sun.applet.AppletThreadGroup");
                return atgClass.isInstance(tg) ? tg.getParent() : null;
            } catch (ClassNotFoundException ex) {
                return null;
            }
        }

        public Thread newThread(Runnable task) {
            final String name = "gstreamer service thread " + counter.incrementAndGet();
            Thread t = new Thread(getThreadGroup(), task, name);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    };

    private static String getField(Class<? extends NativeObject> cls, String name)
            throws SecurityException, IllegalArgumentException {
        try {
            Field f = cls.getDeclaredField(name);
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) && Modifier.isFinal(mod) && f.getType().equals(String.class)) {
                f.setAccessible(true);
                return (String) f.get(null);
            }
        } catch (NoSuchFieldException e) {
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static synchronized void registerClass(Class<? extends NativeObject> cls) {
        String value = null;
        value = getField(cls, "GTYPE_NAME");
        if (value != null) {
            GstTypes.registerType(cls, value);
        }
        value = getField(cls, "GST_NAME");
        if (Element.class.isAssignableFrom(cls) && value != null) {
            ElementFactory.registerElement((Class<? extends Element>) cls, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static synchronized void loadAllClasses() {
        for (Class<? extends NativeObject> cls : nativeClasses) {
            registerClass(cls);
        }
    }
    // to generate the list we use:
    // egrep -rl "GST_NAME|GTYPE_NAME" src 2>/dev/null | egrep -v ".svn|Gst.java" | sort
    // even though the best would be all subclasses of NativeObject
    @SuppressWarnings("rawtypes")
    private static List<Class<? extends NativeObject>> nativeClasses
            = Arrays.<Class<? extends NativeObject>>asList(
                    GDate.class,
                    GInetAddress.class,
                    GSocket.class,
                    GSocketAddress.class,
                    GInetSocketAddress.class,
                    TagList.class,
                    // ----------- Base -------------
                    Buffer.class,
                    BufferPool.class,
                    Bus.class,
                    Caps.class,
                    Clock.class,
                    DateTime.class,
                    Element.class,
                    ElementFactory.class,
                    Event.class,
                    GhostPad.class,
                    Message.class,
                    Pad.class,
                    PadTemplate.class,
                    Plugin.class,
                    PluginFeature.class,
                    Promise.class,
                    Query.class,
                    Registry.class,
                    SDPMessage.class,
                    Sample.class,
                    WebRTCSessionDescription.class,
                    // ----------- Elements -------------
                    AppSink.class,
                    AppSrc.class,
                    BaseSrc.class,
                    BaseSink.class,
                    BaseTransform.class,
                    Bin.class,
                    DecodeBin.class,
                    Pipeline.class,
                    PlayBin.class,
                    URIDecodeBin.class,
                    WebRTCBin.class
            );

    /**
     * Annotation on classes, methods or fields to show the required GStreamer
     * version. This should particularly be used where the version required is
     * higher than the current baseline supported version (GStreamer 1.8)
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    public static @interface Since {

        public int major() default 1;

        public int minor();
    }

}
