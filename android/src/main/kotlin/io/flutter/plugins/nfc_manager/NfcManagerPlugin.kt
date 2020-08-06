package io.flutter.plugins.nfc_manager

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.nfc.tech.TagTechnology
import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.*
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.*

class NfcManagerPlugin: MethodCallHandler,ActivityAware,FlutterPlugin {

    private val cachedTags = mutableMapOf<String, Tag>()
    private var adapter:NfcAdapter? = null
    private var connectedTech: TagTechnology? = null
    private var activity: Activity? = null
    private var channel: MethodChannel? = null

//    companion object {
//        @JvmStatic
//        fun registerWith(registrar: Registrar) {
//            val channel = MethodChannel(registrar.messenger(), "plugins.flutter.io/nfc_manager")
//            channel.setMethodCallHandler(NfcManagerPlugin(channel))
//        }
//    }



    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "isAvailable" -> handleIsAvailable(call, result)
            "onPause" -> handleOnPause(call,result)
            "startTagSession" -> handleStartTagSession(call, result)
            "stopSession" -> handleStopSession(call, result)
            "disposeTag" -> handleDisposeTag(call, result)
            "NfcV#transceive" -> handleTransceive(NfcV::class.java, call, result)

            else -> result.notImplemented()
        }
    }

    private fun handleOnPause(@NonNull call: MethodCall,@NonNull result: Result) {
        Log.d("handleOnPause","handleOnPause Called")

        activity?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                adapter?.disableReaderMode(it)
            }
            it.moveTaskToBack(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                it.finishAndRemoveTask()
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        result.success(null)
    }


    private fun handleIsAvailable(@NonNull call: MethodCall, @NonNull result: Result) {
        adapter?.let {
            if (it.isEnabled) {
                result.success(true)
            } else {
                result.success(false)
            }

        } ?: run {
            result.success(false)
        }
    }


    private fun handleStartTagSession(@NonNull call: MethodCall, @NonNull result: Result) {
        Log.d("check activity",activity.toString())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            result.error("unavailable", "Requires API level 19.", null)
        } else {
            adapter?.let {
                it.enableReaderMode(activity!!,{ tag ->
                    val handle = UUID.randomUUID().toString()
                    cachedTags[handle] = tag
                    activity!!.runOnUiThread { channel?.invokeMethod("onTagDiscovered", serialize(tag).toMutableMap().apply { put("handle", handle) }) }
                }, flagsFrom(call.argument<List<Int>>("pollingOptions")!!), null)

            }
            result.success(true)
        }
    }

    private fun handleStopSession(@NonNull call: MethodCall, @NonNull result: Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            result.error("unavailable", "Requires API level 19.", null)
        } else {
            adapter?.disableReaderMode(activity!!)

            result.success(true)
        }
    }

    private fun handleDisposeTag(@NonNull call: MethodCall, @NonNull result: Result) {
        val handle = call.argument<String>("handle")!!

        val tag = cachedTags.remove(handle) ?: run {
            result.success(true)
            return
        }

        connectedTech?.let { tech ->
            if (tech.tag == tag && tech.isConnected) {
                try { tech.close() } catch (e: IOException) { /* Do nothing */ }
            }
            connectedTech = null
        }

        result.success(true)
    }





    private fun handleTransceive(techClass: Class<out TagTechnology>, @NonNull call: MethodCall, @NonNull result: Result) {
        val handle = call.argument<String>("handle")!!
        val data = call.argument<ByteArray>("data")!!

        val tag = cachedTags[handle] ?: run {
            result.error("not_found", "Tag is not found.", null)
            return
        }

        val tech = techFrom(tag, techClass.name) ?: run {
            result.error("tech_unsupported", "Tag does not support ${techClass.name}.", null)
            return
        }

        try {
            val transceiveMethod = techClass.getMethod("transceive", ByteArray::class.java)
            forceConnect(tech)
            result.success(transceiveMethod.invoke(tech, data))
        } catch (e: IOException) {
            result.error("io_exception", e.localizedMessage, null)
        } catch (e: IllegalAccessException) {
            result.error("illegal_access_exception", e.localizedMessage, null)
        } catch (e: InvocationTargetException) {
            result.error("invocation_target_exception", e.localizedMessage, null)
        }
    }

    @Throws(IOException::class)
    private fun forceConnect(tech: TagTechnology) {
        connectedTech?.let {
            if (it.tag == tech.tag && it::class.java.name == tech::class.java.name) return
            try { it.close() } catch (e: IOException) { /* Do nothing */ }
            tech.connect()
            connectedTech = tech
        } ?: run {
            tech.connect()
            connectedTech = tech
        }
    }



    override fun onDetachedFromActivity() {
        Log.d("onDetachedFromActivity","onDetachedFromActivity")


    }


    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        Log.d("onReattach","onReattachedToActivityForConfigChanges")
        activity = binding.activity
        adapter = NfcAdapter.getDefaultAdapter(activity)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Log.d("onAttachedToActivity","onAttachedToActivity")
        activity = binding.activity
        adapter = NfcAdapter.getDefaultAdapter(activity)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        Log.d("onDetachedForCC","onDetachedFromActivityForConfigChanges")


    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d("onAttachedToEngine","onAttachedToEngine")
        channel = MethodChannel(binding.binaryMessenger, "plugins.flutter.io/nfc_manager")
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {

    }
}
