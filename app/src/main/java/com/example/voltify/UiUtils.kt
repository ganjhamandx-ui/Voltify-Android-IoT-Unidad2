package com.example.voltify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.voltify.data.VoltifyRepositoryProvider

fun Context.dp(value:Int):Int=(value*resources.displayMetrics.density).toInt()

fun Activity.applySystemBars(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

inline fun <reified T : Activity> Activity.openScreen() = startActivity(Intent(this, T::class.java))

fun Activity.goToLoginAndClearTask() {
    VoltifyRepositoryProvider.get(this).logout()
    val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
    startActivity(intent)
}

fun Activity.goToDashboardAndClearTask() {
    val intent = Intent(this, DashboardActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
    startActivity(intent)
}

fun Activity.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

fun Activity.makeText(text:String, size:Float=14f, colorRes:Int=R.color.voltify_text_secondary, bold:Boolean=false):TextView = TextView(this).apply {
    this.text=text; textSize=size; setTextColor(ContextCompat.getColor(this@makeText,colorRes)); if(bold) setTypeface(typeface,Typeface.BOLD)
    layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
}

fun Activity.makeActionButton(text:String, primary:Boolean=false, danger:Boolean=false):Button = Button(this).apply {
    this.text=text; isAllCaps=false; textSize=13f; setTypeface(typeface,Typeface.BOLD)
    background=ContextCompat.getDrawable(this@makeActionButton,if(primary)R.drawable.bg_button_primary else R.drawable.bg_button_outline)
    backgroundTintList=null
    setTextColor(ContextCompat.getColor(this@makeActionButton,when{danger->R.color.voltify_danger;primary->R.color.voltify_text_primary;else->R.color.voltify_bright_blue}))
    layoutParams=LinearLayout.LayoutParams(0,48.dp(this@makeActionButton),1f).apply { marginEnd=6.dp(this@makeActionButton) }
}

private fun Int.dp(context:Context)=context.dp(this)

fun Activity.makePanel():LinearLayout=LinearLayout(this).apply {
    orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(14),dp(14),dp(14)); background=ContextCompat.getDrawable(this@makePanel,R.drawable.bg_panel)
    layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(10) }
}
