package com.pranav.java.ide.compiler

import android.content.Context
import android.os.Looper
import android.util.Log

import com.pranav.android.exception.CompilationFailedException
import com.pranav.android.task.JavaBuilder
import com.pranav.android.task.java.*
import com.pranav.android.task.kotlin.KotlinCompiler
import com.pranav.android.task.dex.D8Task
import com.pranav.android.task.exec.ExecuteDexTask
import com.pranav.common.util.FileUtil
import com.pranav.java.ide.MainActivity
import com.pranav.java.ide.R
import com.pranav.project.mode.JavaProject

import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

class CompileTask(
        context: MainActivity,
        isExecuteMethod: Boolean,
        listeners: CompilerListeners
): Thread() {

    private var d8Time: Long = 0
    private var ecjTime: Long = 0

    private val showExecuteDialog: Boolean

    private val activity: MainActivity

    private val listener: CompilerListeners
    private val builder: JavaBuilder

    private val STAGE_CLEAN: String
    private val STAGE_KOTLINC: String
    private val STAGE_JAVAC: String
    private val STAGE_ECJ: String
    private val STAGE_D8: String
    private val STAGE_LOADING_DEX: String

    init {
        activity = context
        listener = listeners
        showExecuteDialog = isExecuteMethod
        builder = JavaBuilder(activity)

        STAGE_CLEAN = context.getString(R.string.stage_clean)
        STAGE_KOTLINC = context.getString(R.string.stage_kotlinc)
        STAGE_JAVAC = context.getString(R.string.stage_javac)
        STAGE_ECJ = context.getString(R.string.stage_ecj)
        STAGE_D8 = context.getString(R.string.stage_d8)
        STAGE_LOADING_DEX = context.getString(R.string.stage_loading_dex)
    }

    override fun run() {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }

         val prefs = activity.getSharedPreferences("compiler_settings", Context.MODE_PRIVATE)
        try {
            listener.onCurrentBuildStageChanged(STAGE_CLEAN)
            // a simple workaround to prevent calls to system.exit
            val code =
                    activity.editor
                            .getText()
                            .toString()
                            .replace("System.exit(", "System.err.println(\"Exit code \" + ")
            val currentPath = activity.currentWorkingFilePath
            if (code != FileUtil.readFile(File(currentPath))) {
                FileUtil.writeFile(currentPath, code)
            }
        } catch (e: IOException) {
            listener.onFailed(e.getMessage())
        }

        // Run kotlinc
        var time = System.currentTimeMillis()
        listener.onCurrentBuildStageChanged(STAGE_KOTLINC)
        try {
            KotlinCompiler().doFullTask(activity.getProject())
        } catch (e: CompilationFailedException) {
            listener.onFailed(e.getMessage())
            return
        } catch (e: Throwable) {
            listener.onFailed(Log.getStackTraceString(e))
            return
        }
        // Compile Java Files
        try {
            if (prefs.getString("compiler", "Javac").equals("Javac")) {
                listener.onCurrentBuildStageChanged(STAGE_JAVAC)
                JavacCompilationTask(prefs).doFullTask(activity.getProject())
            } else {
                listener.onCurrentBuildStageChanged(STAGE_ECJ);
                ECJCompilationTask(prefs).doFullTask(activity.getProject())
            }
        } catch (e: CompilationFailedException) {
            listener.onFailed(e.getMessage())
            return
        } catch (e: Throwable) {
            listener.onFailed(Log.getStackTraceString(e))
            return
        }

        ecjTime = System.currentTimeMillis() - time
        time = System.currentTimeMillis()

        // run d8
        listener.onCurrentBuildStageChanged(STAGE_D8)
        try {
            D8Task().doFullTask(activity.getProject())
        } catch (e: Exception) {
            listener.onFailed(e.getMessage())
            return
        }
        d8Time = System.currentTimeMillis() - time

        listener.onSuccess()

        // Code that executes the final dex
        try {
            val classes = activity.getClassesFromDex()
            if (classes == null) {
                return
            }
            if (showExecuteDialog) {
                activity.listDialog(
                        "Select a class to execute",
                        classes,
                        { dialog, item ->
                            val task = ExecuteDexTask(prefs, classes.get(item))
                            try {
                                task.doFullTask(activity.getProject())
                            } catch (e: InvocationTargetException) {
                                activity.dialog(
                                        "Failed...",
                                        "Runtime error: "
                                                + e.getMessage()
                                                + "\n\nSystem logs:\n"
                                                + task.getLogs(),
                                        true)
                                return
                            } catch (e: Exception) {
                                activity.dialog(
                                        "Failed...",
                                        "Couldn't execute the dex: "
                                                + e.toString()
                                                + "\n\nSystem logs:\n"
                                                + task.getLogs()
                                                + "\n"
                                                + Log.getStackTraceString(e),
                                        true)
                                return
                            }
                            val s = StringBuilder()

                            s.append("Compiling took: ")
                            s.append(String.valueOf(ecjTime))
                            s.append("ms, ")
                            s.append("D8")
                            s.append(" took: ")
                            s.append(String.valueOf(d8Time))
                            s.append("ms")

                            activity.dialog(s.toString(), task.getLogs(), true)
                        })
            }
        } catch (e: Throwable) {
            listener.onFailed(e.getMessage())
        }
    }

    interface CompilerListeners {
        fun onCurrentBuildStageChanged(stage: String)

        fun onSuccess()

        fun onFailed(errorMessage: String)
    }
}
