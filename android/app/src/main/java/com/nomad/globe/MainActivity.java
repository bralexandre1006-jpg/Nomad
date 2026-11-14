package com.nomad.globe;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaImportPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
