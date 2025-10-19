package com.lizongying.mytv0

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment

class ConfirmationFragment(
    private val listener: ConfirmationListener,
    private val message: String,
    private val update: Boolean
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val builder = AlertDialog.Builder(it)
            builder.setTitle("版本更新")
                .setMessage(message)   // ✅ 把完整文本放这里
                .setPositiveButton("确定") { _, _ ->
                    listener.onConfirm()
                }
                .setNegativeButton("取消") { _, _ ->
                    listener.onCancel()
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    interface ConfirmationListener {
        fun onConfirm()
        fun onCancel()
    }
}