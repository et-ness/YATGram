@file:Suppress("UNCHECKED_CAST")

package org.telegram.messenger.yatgram

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.setPadding
import com.google.zxing.*
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.telegram.messenger.*
import org.telegram.messenger.browser.Browser
import org.telegram.tgnet.TLRPC
import java.io.File
import java.util.*

object Util {

    @JvmStatic
    fun getOwnerActivity(ctx: Context): Activity {
        if (ctx is Activity) return ctx
        if (ctx is ContextWrapper) return getOwnerActivity(ctx.baseContext)
        error("unable cast ${ctx.javaClass.name} to activity")
    }

    @JvmStatic
    @JvmOverloads
    fun showQrDialog(ctx: Context, text: String, icon: (Bitmap)? = null): AlertDialog {

        val code = createQRCode(text, icon = icon)

        ctx.setTheme(R.style.Theme_TMessages)

        return AlertDialog.Builder(ctx).setView(LinearLayout(ctx).apply {

            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)

            addView(LinearLayout(ctx).apply {
                val root = this

                gravity = Gravity.CENTER
                setBackgroundColor(Color.WHITE)
                setPadding(AndroidUtilities.dp(16f))

                val width = AndroidUtilities.dp(260f)

                addView(ImageView(ctx).apply {

                    setImageBitmap(code)
                    scaleType = ImageView.ScaleType.FIT_XY

                    setOnLongClickListener {
                        val builder = BottomBuilder(ctx)

                        builder.addItems(arrayOf(
                                LocaleController.getString("SaveToGallery", R.string.SaveToGallery),
                                LocaleController.getString("Cancel", R.string.Cancel)
                        ), intArrayOf(
                                R.drawable.baseline_image_24,
                                R.drawable.baseline_cancel_24
                        )) { i, _, _ ->

                            if (i == 0) {
                                if (Build.VERSION.SDK_INT >= 23 && ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                    getOwnerActivity(ctx).requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 4)
                                    return@addItems
                                }

                                val saveTo = File(Environment.getExternalStorageDirectory(), "${Environment.DIRECTORY_PICTURES}/share_${text.hashCode()}.jpg")
                                saveTo.parentFile?.mkdirs()

                                runCatching {
                                    saveTo.createNewFile()
                                    saveTo.outputStream().use {
                                        loadBitmapFromView(root).compress(Bitmap.CompressFormat.JPEG, 100, it);
                                    }
                                    AndroidUtilities.addMediaToGallery(saveTo.path)
                                    showToast(LocaleController.getString("PhotoSavedHint", R.string.PhotoSavedHint))
                                }.onFailure {
                                    FileLog.e(it)
                                    showToast(it)
                                }
                            }
                        }
                        builder.show()
                        return@setOnLongClickListener true
                    }
                }, LinearLayout.LayoutParams(width, width))
            }, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER
            })
        }).create().apply {
            show()
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun loadBitmapFromView(v: View): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        v.layout(v.left, v.top, v.right, v.bottom)
        v.draw(c)
        return b
    }

    @JvmStatic
    fun createQRCode(text: String, size: Int = 768, icon: (Bitmap)? = null): Bitmap {
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            //QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints, null, null, icon)
            QRCodeWriter().encode(text, size, size, hints, icon)
        } catch (e: WriterException) {
            FileLog.e(e);
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        }
    }

    val qrReader = QRCodeReader()

    @JvmStatic
    fun tryReadQR(ctx: Activity, bitmap: Bitmap) {
        val intArray = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)

        try {
            val result = try {
                qrReader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), mapOf(
                        DecodeHintType.TRY_HARDER to true
                ))
            } catch (e: NotFoundException) {
                qrReader.decode(BinaryBitmap(GlobalHistogramBinarizer(source.invert())), mapOf(
                        DecodeHintType.TRY_HARDER to true
                ))
            }
            showLinkAlert(ctx, result.text)
        } catch (e: Throwable) {
            showToast(LocaleController.getString("NoQrFound", R.string.NoQrFound))
        }
    }

    @JvmStatic
    @JvmOverloads
    fun showLinkAlert(ctx: Activity, text: String, tryInternal: Boolean = true) {

        val builder = BottomBuilder(ctx)

        if (tryInternal) {
            runCatching {
                if (Browser.isInternalUrl(text, booleanArrayOf(false))) {
                    Browser.openUrl(ctx, text)
                    return
                }
            }
        }

        builder.addTitle(text)

        builder.addItems(arrayOf(
                LocaleController.getString("Open", R.string.Open),
                LocaleController.getString("Copy", R.string.Copy),
                LocaleController.getString("ShareQRCode", R.string.ShareQRCode)
        ), intArrayOf(
                R.drawable.baseline_open_in_browser_24,
                R.drawable.baseline_content_copy_24,
                R.drawable.wallet_qr
        )) { which, _, _ ->
            when (which) {
                0 -> Browser.openUrl(ctx, text)
                1 -> {
                    AndroidUtilities.addToClipboard(text)
                    showToast(LocaleController.getString("LinkCopied", R.string.LinkCopied))
                }
                else -> showQrDialog(ctx, text)
            }
        }
        builder.show()
    }

    fun runOnUIThread(runnable: Runnable) = ApplicationLoader.applicationHandler.post(runnable)

    @JvmStatic
    fun showToast(e: Throwable) = showToast(e.message ?: e.javaClass.simpleName)

    @JvmStatic
    fun showToast(e: TLRPC.TL_error?) {
        if (e == null) return
        showToast("${e.code}: ${e.text}")
    }

    @JvmStatic
    fun showToast(text: String) = runOnUIThread(Runnable {
        Toast.makeText(
                ApplicationLoader.applicationContext,
                text.takeIf { it.isNotBlank() }
                        ?: "Errore !",
                Toast.LENGTH_LONG
        ).show()
    })
}
