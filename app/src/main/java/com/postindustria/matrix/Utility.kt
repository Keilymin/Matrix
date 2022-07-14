package com.postindustria.matrix

import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.lang.Float.min

class Utility {
    companion object {
        fun computeTransformationMatrix(
            textureView: TextureView,
            characteristics: CameraCharacteristics,
            previewSize: Size,
            surfaceRotation: Int
        ): Matrix {
            val matrix = Matrix()

            val surfaceRotationDegrees = when (surfaceRotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

            /* Rotation required to transform from the camera sensor orientation to the
         * device's current orientation in degrees. */
            val relativeRotation = computeRelativeRotation(characteristics, surfaceRotationDegrees)

            /* Scale factor required to scale the preview to its original size on the x-axis. */
            val scaleX =
                if (relativeRotation % 180 == 0) {
                    textureView.width.toFloat() / previewSize.width
                } else {
                    textureView.width.toFloat() / previewSize.height
                }
            /* Scale factor required to scale the preview to its original size on the y-axis. */
            val scaleY =
                if (relativeRotation % 180 == 0) {
                    textureView.height.toFloat() / previewSize.height
                } else {
                    textureView.height.toFloat() / previewSize.width
                }

            /* Scale factor required to fit the preview to the TextureView size. */
            val finalScale = min(scaleX, scaleY)

            /* The scale will be different if the buffer has been rotated. */
            if (relativeRotation % 180 == 0) {
                matrix.setScale(
                    textureView.height / textureView.width.toFloat() / scaleY * finalScale,
                    textureView.width / textureView.height.toFloat() / scaleX * finalScale,
                    textureView.width / 2f,
                    textureView.height / 2f
                )
            } else {
                matrix.setScale(
                    1 / scaleX * finalScale,
                    1 / scaleY * finalScale,
                    textureView.width / 2f,
                    textureView.height / 2f
                )
            }

            // Rotate the TextureView to compensate for the Surface's rotation.
            matrix.postRotate(
                -surfaceRotationDegrees.toFloat(),
                textureView.width / 2f,
                textureView.height / 2f
            )

            return matrix
        }

        fun computeRelativeRotation(
            characteristics: CameraCharacteristics,
            surfaceRotationDegrees: Int
        ): Int {
            val sensorOrientationDegrees =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

            // Reverse device orientation for back-facing cameras.
            val sign = if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
            ) 1 else -1

            // Calculate desired orientation relative to camera orientation to make
            // the image upright relative to the device orientation.
            return (sensorOrientationDegrees - surfaceRotationDegrees * sign + 360) % 360
        }
    }
}