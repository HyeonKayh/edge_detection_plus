package com.sample.edgedetection.scan
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.widget.RelativeLayout
import android.widget.Toast
import com.sample.edgedetection.EdgeDetectionHandler
import com.sample.edgedetection.REQUEST_CODE
import com.sample.edgedetection.SourceManager
import com.sample.edgedetection.crop.CropActivity
import com.sample.edgedetection.processor.Corners
import com.sample.edgedetection.processor.processPicture
import com.sample.edgedetection.view.CardGuideView
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Core.ROTATE_90_CLOCKWISE
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.core.MatOfByte
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class ScanPresenter constructor(
    private val context: Context,
    private val iView: IScanView.Proxy,
    private val initialBundle: Bundle
) :
    SurfaceHolder.Callback, Camera.PictureCallback, Camera.PreviewCallback {
    private val TAG: String = "ScanPresenter"
    private var mCamera: Camera? = null
    private val mSurfaceHolder: SurfaceHolder = iView.getSurfaceView().holder
    private val executor: ExecutorService
    private val proxySchedule: Scheduler
    private var busy: Boolean = false
    private var flashEnabled: Boolean = false

    private var mLastClickTime = 0L
    private var shutted: Boolean = true

    private val cardGuideEnabled =
        initialBundle.getBoolean(EdgeDetectionHandler.CARD_GUIDE, false)
    private var guideStableCount = 0

    companion object {
        // 손떨림으로 인한 오탐 방지를 위해 연속 프레임 유지 시에만 자동 촬영
        private const val GUIDE_STABLE_THRESHOLD = 6

        // 프리뷰 비율 비교 시 이 간격 안이면 같은 비율대로 보고 해상도가 높은 쪽을 고른다
        private const val PREVIEW_RATIO_BUCKET = 0.05f
    }

    init {
        mSurfaceHolder.addCallback(this)
        executor = Executors.newSingleThreadExecutor()
        proxySchedule = Schedulers.from(executor)
    }

    private fun isOpenRecently(): Boolean {
        if (SystemClock.elapsedRealtime() - mLastClickTime < 3000) {
            return true
        }
        mLastClickTime = SystemClock.elapsedRealtime()
        return false
    }

    fun start() {
        mCamera?.startPreview() ?:
        Log.i(TAG, "mCamera startPreview")
    }

    fun stop() {
        mCamera?.stopPreview() ?:
        Log.i(TAG, "mCamera stopPreview")
    }

    val canShut: Boolean get() = shutted

    fun shut() {
        if (isOpenRecently()) {
            Log.i(TAG, "NOT Taking click")
            return
        }
        busy = true
        shutted = false
        Log.i(TAG, "try to focus")

        mCamera?.autoFocus { b, _ ->
            Log.i(TAG, "focus result: $b")
            mCamera?.enableShutterSound(false)
            mCamera?.takePicture(null, null, this)
        }

    }

    fun toggleFlash() {
        try {
            flashEnabled = !flashEnabled
            val parameters = mCamera?.parameters
            parameters?.flashMode =
                if (flashEnabled) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            mCamera?.parameters = parameters
            mCamera?.startPreview()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updateCamera() {
        if (null == mCamera) {
            return
        }
        mCamera?.stopPreview()
        try {
            mCamera?.setPreviewDisplay(mSurfaceHolder)
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }
        mCamera?.setPreviewCallback(this)
        mCamera?.startPreview()
    }

    private fun initCamera() {

        try {
            mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
        } catch (e: RuntimeException) {
            e.stackTrace
            Toast.makeText(context, "cannot open camera, please grant camera", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val display = iView.getCurrentDisplay()
        val point = Point()

        display?.getRealSize(point)

        val displayWidth = minOf(point.x, point.y)
        val displayHeight = maxOf(point.x, point.y)

        // 화면 비율과 가장 가까운 프리뷰(비슷한 비율 중 최대 해상도)를 골라 레터박스를 최소화
        val displayLandscapeRatio = displayHeight.toFloat() / displayWidth
        val size = mCamera?.parameters?.supportedPreviewSizes
            ?.filter { it.height <= 1080 && it.width <= 2560 }
            ?.sortedWith(compareBy(
                { (abs(it.width.toFloat() / it.height - displayLandscapeRatio) / PREVIEW_RATIO_BUCKET).toInt() },
                { -it.width * it.height }
            ))
            ?.firstOrNull()

        Log.i(TAG, "Selected preview size: ${size?.width}x${size?.height}")

        val param = mCamera?.parameters
        param?.setPreviewSize(size?.width ?: 1920, size?.height ?: 1080)

        val displayRatio = displayWidth.div(displayHeight.toFloat())
        val previewRatio = size?.height?.toFloat()?.div(size.width.toFloat()) ?: displayRatio

        // 프리뷰 비율 그대로 레터박스 배치해 화면비가 달라도 영상이 늘어나지 않게 한다.
        // 꼭짓점 좌표 매핑이 어긋나지 않도록 오버레이 뷰들도 서페이스와 같은 크기로 맞춘다.
        val surfaceWidth: Int
        val surfaceHeight: Int
        if (displayRatio > previewRatio) {
            surfaceWidth = (displayHeight * previewRatio).toInt()
            surfaceHeight = displayHeight
        } else {
            surfaceWidth = displayWidth
            surfaceHeight = (displayWidth / previewRatio).toInt()
        }
        listOf(iView.getSurfaceView(), iView.getPaperRect(), iView.getCardGuide()).forEach { view ->
            val params = view.layoutParams as RelativeLayout.LayoutParams
            params.width = surfaceWidth
            params.height = surfaceHeight
            params.addRule(RelativeLayout.CENTER_IN_PARENT)
            view.layoutParams = params
        }

        val supportPicSize = mCamera?.parameters?.supportedPictureSizes
        supportPicSize?.sortByDescending { it.width.times(it.height) }
        var pictureSize = supportPicSize?.find {
            it.height.toFloat().div(it.width.toFloat()) - previewRatio < 0.01
        }

        if (null == pictureSize) {
            pictureSize = supportPicSize?.get(0)
        }

        if (null == pictureSize) {
            Log.e(TAG, "can not get picture size")
        } else {
            param?.setPictureSize(pictureSize.width, pictureSize.height)
        }
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS) && mCamera!!.parameters.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
        {
            param?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            Log.i(TAG, "enabling autofocus")
        } else {
            Log.i(TAG, "autofocus not available")
        }

        param?.flashMode = Camera.Parameters.FLASH_MODE_OFF

        mCamera?.parameters = param
        mCamera?.setDisplayOrientation(90)
        mCamera?.enableShutterSound(false)
    }

    private fun matrixResizer(sourceMatrix: Mat): Mat {
        val sourceSize: Size = sourceMatrix.size()
        var copied = Mat()
        if (sourceSize.height < sourceSize.width) {
            Core.rotate(sourceMatrix, copied, ROTATE_90_CLOCKWISE)
        } else {
            copied = sourceMatrix
        }
        val copiedSize: Size = copied.size()
        return if (copiedSize.width > ScanConstants.MAX_SIZE.width || copiedSize.height > ScanConstants.MAX_SIZE.height) {
            var useRatio = 0.0
            val widthRatio: Double = ScanConstants.MAX_SIZE.width / copiedSize.width
            val heightRatio: Double = ScanConstants.MAX_SIZE.height / copiedSize.height
            useRatio = if(widthRatio > heightRatio)  widthRatio else heightRatio
            val resizedImage = Mat()
            val newSize = Size(copiedSize.width * useRatio, copiedSize.height * useRatio)
            Imgproc.resize(copied, resizedImage, newSize)
            resizedImage
        } else {
            copied
        }
    }
    fun detectEdge(pic: Mat) {
        Log.i("height", pic.size().height.toString())
        Log.i("width", pic.size().width.toString())
        val resizedMat = matrixResizer(pic)
        SourceManager.corners = processPicture(resizedMat)
        Imgproc.cvtColor(resizedMat, resizedMat, Imgproc.COLOR_RGB2BGRA)
        SourceManager.pic = resizedMat
        val cropIntent = Intent(context, CropActivity::class.java)
        cropIntent.putExtra(EdgeDetectionHandler.INITIAL_BUNDLE, this.initialBundle)
        (context as Activity).startActivityForResult(cropIntent, REQUEST_CODE)
    }

    private fun checkCardGuide(corners: Corners) {
        val guideView: CardGuideView = iView.getCardGuide()
        if (guideView.measuredWidth == 0 || guideView.measuredHeight == 0) {
            return
        }

        val ratioX = corners.size.width.div(guideView.measuredWidth)
        val ratioY = corners.size.height.div(guideView.measuredHeight)
        val viewPoints = corners.corners.filterNotNull().map {
            org.opencv.core.Point(it.x / ratioX, it.y / ratioY)
        }

        val inside = guideView.contains(viewPoints)
        guideView.setDetected(inside)

        if (inside) {
            guideStableCount++
            if (guideStableCount >= GUIDE_STABLE_THRESHOLD && canShut) {
                guideStableCount = 0
                shut()
            }
        } else {
            guideStableCount = 0
        }
    }

    override fun surfaceCreated(p0: SurfaceHolder) {
        initCamera()
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        updateCamera()
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        synchronized(this) {
            mCamera?.stopPreview()
            mCamera?.setPreviewCallback(null)
            mCamera?.release()
            mCamera = null
        }
    }

    override fun onPictureTaken(p0: ByteArray?, p1: Camera?) {
        Log.i(TAG, "on picture taken")

        if (p0 == null || p0.isEmpty()) {
            busy = false
            return
        }

        Observable.just(p0)
            .subscribeOn(proxySchedule)
            .subscribe({ data ->
                val pictureSize = p1?.parameters?.pictureSize
                Log.i(TAG, "picture size: $pictureSize")

                val buffer = MatOfByte(*data)
                val pic = Imgcodecs.imdecode(buffer, Imgcodecs.IMREAD_UNCHANGED)

                if (pic.empty()) {
                    buffer.release()
                    busy = false
                    return@subscribe
                }

                Core.rotate(pic, pic, Core.ROTATE_90_CLOCKWISE)
                buffer.release()

                detectEdge(pic)
                shutted = true
                busy = false

            }, { e ->
                Log.e(TAG, "onPictureTaken error", e)
                busy = false
            })
    }

    override fun onPreviewFrame(p0: ByteArray?, p1: Camera?) {
        if (busy) {
            return
        }
        busy = true
        try {
            Observable.just(p0)
                .observeOn(proxySchedule)
                .doOnError {}
                .subscribe({
                    val parameters = p1?.parameters
                    val width = parameters?.previewSize?.width
                    val height = parameters?.previewSize?.height
                    val yuv = YuvImage(
                        p0, parameters?.previewFormat ?: 0, width ?: 1080, height
                            ?: 1920, null
                    )
                    val out = ByteArrayOutputStream()
                    yuv.compressToJpeg(Rect(0, 0, width ?: 1080, height ?: 1920), 100, out)
                    val bytes = out.toByteArray()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val img = Mat()
                    Utils.bitmapToMat(bitmap, img)
                    bitmap.recycle()
                    Core.rotate(img, img, Core.ROTATE_90_CLOCKWISE)
                    try {
                        out.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                    Observable.create<Corners> {
                        val corner = processPicture(img)
                        busy = false
                        if (null != corner && corner.corners.size == 4) {
                            it.onNext(corner)
                        } else {
                            it.onError(Throwable("paper not detected"))
                        }
                    }.observeOn(AndroidSchedulers.mainThread())
                        .subscribe({
                            // PaperRectangle이 꼭짓점 좌표를 뷰 좌표로 변형(resize)하므로 판정을 먼저 수행
                            if (cardGuideEnabled) {
                                checkCardGuide(it)
                            }
                            iView.getPaperRect().onCornersDetected(it)

                        }, {
                            if (cardGuideEnabled) {
                                guideStableCount = 0
                                iView.getCardGuide().setDetected(false)
                            }
                            iView.getPaperRect().onCornersNotDetected()
                        })
                }, { throwable -> Log.e(TAG, throwable.message!!) })
        } catch (e: Exception) {
            print(e.message)
        }

    }

}
