package com.sample.edgedetection.base

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sample.edgedetection.R

abstract class BaseActivity : AppCompatActivity() {

    protected val TAG = this.javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(provideContentViewId())
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        initPresenter()
        opaqueStatusBar()
        prepare()
    }

    // 상태바를 앱바와 같은 불투명 색으로 덮고 콘텐츠는 그 아래부터 배치한다
    private fun opaqueStatusBar(
        statusBarColor: Int = resources.getColor(R.color.colorPrimary)
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = statusBarColor
        }
        // targetSdk 35 강제 edge-to-edge에서는 statusBarColor가 무시되고
        // 상태바 뒤로 윈도우 배경이 보이므로 배경 자체를 같은 색으로 채운다
        window.setBackgroundDrawable(ColorDrawable(statusBarColor))
    }

    // edge-to-edge에서 하단 내비게이션 바에 가려지지 않도록 인셋만큼 패딩을 더한다
    protected fun applyBottomInsetPadding(view: View) {
        val basePadding = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottomInset)
            insets
        }
    }

    abstract fun provideContentViewId(): Int

    abstract fun initPresenter()

    abstract fun prepare()
}
