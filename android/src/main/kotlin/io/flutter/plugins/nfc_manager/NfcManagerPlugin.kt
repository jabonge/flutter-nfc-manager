package io.flutter.plugins.nfc_manager

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED
import android.nfc.NfcAdapter.EXTRA_TAG
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.nfc.tech.TagTechnology
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.*
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.*

class NfcManagerPlugin() : MethodCallHandler,ActivityAware,FlutterPlugin, PluginRegistry.NewIntentListener {

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


        activity?.let {


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

        adapter?.let {
            val intent = Intent(activity!!.applicationContext, activity!!::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            val pendingIntent: PendingIntent = PendingIntent.getActivity(activity!!.applicationContext, 0, intent, 0)

            it.enableForegroundDispatch(activity, pendingIntent, null, null)

        }
        result.success(true)

    }

    private fun handleStopSession(@NonNull call: MethodCall, @NonNull result: Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            result.error("unavailable", "Requires API level 19.", null)
        } else {
            adapter?.disableForegroundDispatch(activity!!)

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

    private fun startReadingWithForegroundDispatch(@NonNull call: MethodCall, @NonNull result: Result) {


    }



    override fun onDetachedFromActivity() {



    }


    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {

        activity = binding.activity
        adapter = NfcAdapter.getDefaultAdapter(activity)
        binding.addOnNewIntentListener(this)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {

        activity = binding.activity
        adapter = NfcAdapter.getDefaultAdapter(activity)
        binding.addOnNewIntentListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {



    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {

        channel = MethodChannel(binding.binaryMessenger, "plugins.flutter.io/nfc_manager")
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {

    }




    override fun onNewIntent(intent: Intent?): Boolean {

        val action = intent?.action
        if (ACTION_TAG_DISCOVERED == action) {
            val tag: Tag? = intent.getParcelableExtra(EXTRA_TAG)
            tag?.let {
                it ->
                val handle = UUID.randomUUID().toString()
                cachedTags[handle] = it
                activity?.runOnUiThread { channel?.invokeMethod("onTagDiscovered", serialize(it).toMutableMap().apply { put("handle", handle) }) }
                }
                return true
            }
        return false
        }



}
