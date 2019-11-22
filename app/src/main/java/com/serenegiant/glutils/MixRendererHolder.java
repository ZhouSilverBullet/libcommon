package com.serenegiant.glutils;
/*
 * libcommon
 * utility/helper classes for myself
 *
 * Copyright (c) 2014-2019 saki t_saki@serenegiant.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
*/

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.glutils.es2.GLHelper;
import com.serenegiant.utils.BuildCheck;
import com.serenegiant.utils.HandlerThreadHandler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static com.serenegiant.glutils.ShaderConst.*;

/**
 * Created by saki on 2018/02/14.
 * 2つの動画を合成して表示するためのIRendererHolder実装
 */
public class MixRendererHolder extends AbstractRendererHolder {
	private static final boolean DEBUG = false; // FIXME set false on production
	private static final String TAG = MixRendererHolder.class.getSimpleName();
	
	/**
	 * コンストラクタ
	 * @param width
	 * @param height
	 * @param callback
	 */
	public MixRendererHolder(final int width, final int height,
		@Nullable final RenderHolderCallback callback) {

		this(width, height,
			3, null, EGLConst.EGL_FLAG_RECORDABLE,
			callback);
	}

	/**
	 * コンストラクタ
	 * @param width
	 * @param height
	 * @param maxClientVersion
	 * @param sharedContext
	 * @param flags
	 * @param callback
	 */
	protected MixRendererHolder(final int width, final int height,
		final int maxClientVersion,
		@Nullable final EGLBase.IContext sharedContext, final int flags,
		@Nullable final RenderHolderCallback callback) {

		super(width, height, maxClientVersion, sharedContext, flags, callback);
		if (DEBUG) Log.v(TAG, "コンストラクタ:");
	}

	/**
	 * マスター用の映像を受け取るためのSurfaceを取得
	 * @return
	 */
	public Surface getSurface2() {
		return ((MixRendererTask)mRendererTask).getSurface2();
	}

	/**
	 * マスター用の映像を受け取るためのSurfaceTextureを取得
	 * @return
	 */
	public SurfaceTexture getSurfaceTexture2() {
		return ((MixRendererTask)mRendererTask).getSurfaceTexture2();
	}

	/**
	 * 合成時のマスク用Bitmapをセット
	 * @param bitmap
	 */
	public void setMask(@Nullable final Bitmap bitmap) {
		((MixRendererTask)mRendererTask).setMask(bitmap);
	}
	
	/**
	 * 描画タスクを生成
	 * @param width
	 * @param height
	 * @param maxClientVersion
	 * @param sharedContext
	 * @param flags
	 * @return
	 */
	@NonNull
	@Override
	protected BaseRendererTask createRendererTask(
		final int width, final int height,
		final int maxClientVersion,
		@Nullable final EGLBase.IContext sharedContext, final int flags) {

		return new MixRendererTask(this, width, height,
			maxClientVersion, sharedContext, flags);
	}

	private static final int REQUEST_SET_MASK = 10;

	private static final String FRAGMENT_SHADER_BASE = SHADER_VERSION +
		"%s" +
		"precision highp float;\n" +
		"varying       vec2 vTextureCoord;\n" +
		"uniform %s    sTexture;\n" +	// 入力テクスチャA
		"uniform %s    sTexture2;\n" +	// 入力テクスチャB
		"uniform %s    sTexture3;\n" +	// マスクM
		"void main() {\n" +
		"    highp vec4 tex1 = texture2D(sTexture, vTextureCoord);\n" +
		"    highp vec4 tex2 = texture2D(sTexture2, vTextureCoord);\n" +
		"    highp float alpha = texture2D(sTexture3, vTextureCoord).a;\n" +
		"    gl_FragColor = vec4(mix(tex1.rgb, tex2.rgb, tex2.a * alpha), tex1.a);\n" +
		"}\n";
	private static final String MY_FRAGMENT_SHADER_EXT
		= String.format(FRAGMENT_SHADER_BASE, HEADER_OES,
			SAMPLER_OES, SAMPLER_OES, SAMPLER_OES);

	/**
	 * 描画タスク
	 */
	private final class MixRendererTask extends BaseRendererTask {
		private final float[] mTexMatrix2 = new float[16];
		private int mTexId2;
		private SurfaceTexture mMasterTexture2;
		private Surface mMasterSurface2;

		private final float[] mMaskTexMatrix = new float[16];
		private int mMaskTexId;
		private SurfaceTexture mMaskTexture;
		private Surface mMaskSurface;

		/**
		 * OnFrameAvailable呼び出し用のHandler
		 */
		private Handler mAsyncHandler;
		private volatile boolean mIsFirstFrameRendered;

		public MixRendererTask(@NonNull final AbstractRendererHolder parent,
			final int width, final int height,
			final int maxClientVersion,
			@Nullable final EGLBase.IContext sharedContext, final int flags) {

			super(parent, width, height, maxClientVersion, sharedContext, flags);
			if (BuildCheck.isAndroid5()) {
				mAsyncHandler = HandlerThreadHandler.createHandler("OnFrameAvailable");
			}
			if (DEBUG) Log.v(TAG, "MixRendererTask#コンストラクタ:");
		}

		/**
		 * マスター映像取得用のSurfaceを取得
		 * @return
		 */
		public Surface getSurface2() {
			checkMasterSurface();
			return mMasterSurface2;
		}
	
		/**
		 * マスター映像受け取り用のSurfaceTextureを取得
		 * @return
		 */
		public SurfaceTexture getSurfaceTexture2() {
			checkMasterSurface();
			return mMasterTexture2;
		}

		public void setMask(@Nullable final Bitmap mask) {
			if (DEBUG) Log.v(TAG, "setMask:" + mask);
			checkFinished();
			offer(REQUEST_SET_MASK, 0, 0, mask);
		}

		@SuppressLint("NewApi")
		@Override
		protected void internalOnStart() {
			if (DEBUG) Log.v(TAG, "internalOnStart:");
			super.internalOnStart();
			if (mDrawer instanceof IShaderDrawer2d) {
				if (DEBUG) Log.v(TAG, String.format("internalOnStart:init mix texture(%dx%d)",
					width(), height()));
				final IShaderDrawer2d drawer = (IShaderDrawer2d)mDrawer;
				drawer.updateShader(MY_FRAGMENT_SHADER_EXT);
				final int uTex1 = drawer.glGetUniformLocation("sTexture");
				GLES20.glUniform1i(uTex1, 0);

				// アルファブレンド用テクスチャ/SurfaceTexture/Surfaceを生成
				final int uTex2 = drawer.glGetUniformLocation("sTexture2");
				mTexId2 = GLHelper.initTex(
					GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE1,
					GLES20.GL_LINEAR, GLES20.GL_LINEAR, GLES20.GL_CLAMP_TO_EDGE);
				mMasterTexture2 = new SurfaceTexture(mTexId2);
				mMasterTexture2.setDefaultBufferSize(width(), height());
				mMasterSurface2 = new Surface(mMasterTexture2);
				if (BuildCheck.isAndroid5()) {
					mMasterTexture2.setOnFrameAvailableListener(
						mOnFrameAvailableListener, mAsyncHandler);
				} else {
					mMasterTexture2.setOnFrameAvailableListener(
						mOnFrameAvailableListener);
				}
				GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
				GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTexId2);
				GLES20.glUniform1i(uTex2, 1);

				// マスク用テクスチャ/SurfaceTexture/Surfaceを生成
				final int uTex3 = drawer.glGetUniformLocation("sTexture3");
				mMaskTexId = GLHelper.initTex(
					GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE2,
					GLES20.GL_LINEAR, GLES20.GL_LINEAR, GLES20.GL_CLAMP_TO_EDGE);
				mMaskTexture = new SurfaceTexture(mMaskTexId);
				mMaskTexture.setDefaultBufferSize(width(), height());
				mMaskSurface = new Surface(mMaskTexture);
				GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
				GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mMaskTexId);
				GLES20.glUniform1i(uTex3, 2);
			}
			mIsFirstFrameRendered = false;
		}
		
		@Override
		protected void internalOnStop() {
			if (DEBUG) Log.v(TAG, "internalOnStop:");
			synchronized (MixRendererHolder.this) {
				if (mAsyncHandler != null) {
					try {
						mAsyncHandler.removeCallbacksAndMessages(null);
					} catch (final Exception e) {
						Log.w(TAG, e);
					}
				}
			}
			if (mMasterTexture2 != null) {
				mMasterTexture2.release();
				mMasterTexture2 = null;
			}
			mMasterSurface2 = null;
			if (mTexId2 >= 0) {
				GLHelper.deleteTex(mTexId2);
				mTexId2 = -1;
			}

			if (mMaskTexture != null) {
				mMaskTexture.release();
				mMaskTexture = null;
			}
			mMaskSurface = null;
			if (mMaskTexId >= 0) {
				GLHelper.deleteTex(mMaskTexId);
				mMaskTexId = -1;
			}
			if (mAsyncHandler != null) {
				try {
					mAsyncHandler.getLooper().quit();
				} catch (final Exception e) {
					if (DEBUG) Log.w(TAG, e);
				}
				mAsyncHandler = null;
			}
			if (DEBUG) Log.v(TAG, "internalOnStop:finished");
			super.internalOnStop();
		}
		
		@Override
		protected void handleUpdateTexture() {
			super.handleUpdateTexture();
			if (mIsFirstFrameRendered) {
				mMasterTexture2.updateTexImage();
				mMasterTexture2.getTransformMatrix(mTexMatrix2);
				
				mMaskTexture.updateTexImage();
				mMaskTexture.getTransformMatrix(mMaskTexMatrix);
			}
		}
		
		@Override
		protected void handleResize(final int width, final int height) {
			super.handleResize(width, height);
			if (DEBUG) Log.v(TAG, String.format("handleResize:(%dx%d)", width, height));
			if (mMasterTexture2 != null) {
				mMasterTexture2.setDefaultBufferSize(width(), height());
			}
			if (mMaskTexture != null) {
				mMaskTexture.setDefaultBufferSize(width(), height());
			}
		}

		@Override
		protected Object handleRequest(final int request,
			final int arg1, final int arg2, final Object obj) {

			Object result = null;
			if (request == REQUEST_SET_MASK) {
				handleSetMask((Bitmap)obj);
			} else {
				result = super.handleRequest(request, arg1, arg2, obj);
			}

			return result;
		}
		
		/**
		 * マスク用のBitmapをセットする
		 * Bitmapがnullの時はα=1で全面を塗りつぶす(見えるように赤)
		 * @param mask
		 */
		protected void handleSetMask(@Nullable final Bitmap mask) {
			if (DEBUG) Log.v(TAG, "handleSetMask:" + mask);
			GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
			GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mMaskTexId);
			try {
				final Canvas canvas = mMaskSurface.lockCanvas(null);
				try {
					if (mask != null) {
						canvas.drawBitmap(mask, 0, 0, null);
					} else if (DEBUG) {
						// DEBUGフラグtrueでオーバーレイ映像が設定されていないときは全面を薄赤色にする
						canvas.drawColor(0x7fff0000);	// ARGB
					} else {
						// DEBUGフラグfalseでオーバーレイ映像が設定されていなければ全面透過
						canvas.drawColor(0xff000000);	// ARGB
					}
				} finally {
					mMaskSurface.unlockCanvasAndPost(canvas);
				}
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
			requestFrame();
			if (DEBUG) Log.v(TAG, "handleSetMask:finished");
		}

		public void requestFrame() {
			super.requestFrame();
			mIsFirstFrameRendered = true;
		}

		private int cnt;
		/**
		 * SurfaceTextureでアルファブレンド用映像を受け取った際のコールバックリスナー
		 */
		private final SurfaceTexture.OnFrameAvailableListener
			mOnFrameAvailableListener = new SurfaceTexture.OnFrameAvailableListener() {

			@Override
			public void onFrameAvailable(final SurfaceTexture surfaceTexture) {
//				if (DEBUG) Log.v(TAG, "onFrameAvailable:" + surfaceTexture);
				requestFrame();
				if (DEBUG && (((++cnt) % 100) == 0)) {
					Log.v(TAG, "onFrameAvailable:" + cnt);
				}
			}
		};
	
	}
}
