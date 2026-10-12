package com.testscreen;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.TextView;

public class PortraitLocked extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        text.setText("硬锁竖屏页面\nmanifest: screenOrientation=portrait");
        setContentView(text);
    }
}
