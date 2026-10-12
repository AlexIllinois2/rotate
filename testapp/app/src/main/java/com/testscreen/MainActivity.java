package com.testscreen;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        Button portrait = new Button(this);
        portrait.setText("打开「硬锁竖屏」页面");
        portrait.setOnClickListener(v -> startActivity(new Intent(this, PortraitLocked.class)));
        Button landscape = new Button(this);
        landscape.setText("打开「硬锁横屏」页面");
        landscape.setOnClickListener(v -> startActivity(new Intent(this, LandscapeLocked.class)));
        layout.addView(portrait);
        layout.addView(landscape);
        setContentView(layout);
    }
}
