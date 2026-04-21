package cn.yaasoosoft.mino;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import cn.yaasoosoft.mino.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "mino_prefs";
    private static final String KEY_SELECTED_CONFIG = "selected_config";

    private ActivityMainBinding binding;
    private ConfigRepository repository;
    private final List<File> configFiles = new ArrayList<>();
    private ArrayAdapter<String> spinnerAdapter;

    private final BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!MinoVpnService.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            boolean running = intent.getBooleanExtra(MinoVpnService.EXTRA_RUNNING, false);
            String configName = intent.getStringExtra(MinoVpnService.EXTRA_ACTIVE_CONFIG);
            String statusText = intent.getStringExtra(MinoVpnService.EXTRA_STATUS_TEXT);
            renderStatus(running, configName, statusText);
        }
    };

    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::handleImportResult);

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            });

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startVpnWithSelectedConfig();
                } else {
                    toast("VPN 权限未授权");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        requestNotificationPermissionIfNeeded();

        repository = new ConfigRepository(this);
        spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.configSpinner.setAdapter(spinnerAdapter);
        binding.configSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            if (position >= 0 && position < configFiles.size()) {
                File file = configFiles.get(position);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SELECTED_CONFIG, file.getName()).apply();
                renderConfigDetail(file);
            }
        }));

        binding.importButton.setOnClickListener(v -> importLauncher.launch(new String[]{
                "application/x-yaml",
                "text/yaml",
                "text/x-yaml",
                "*/*"
        }));
        binding.startButton.setOnClickListener(v -> ensureVpnPermissionAndStart());
        binding.stopButton.setOnClickListener(v -> stopVpn());

        refreshConfigList();
        refreshStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(vpnStateReceiver, new IntentFilter(MinoVpnService.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(vpnStateReceiver);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void handleImportResult(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            File file = repository.importConfig(uri, resolveDisplayName(uri));
            refreshConfigList();
            selectConfig(file.getName());
            toast("已导入: " + file.getName());
        } catch (IOException e) {
            toast("导入失败: " + e.getMessage());
        }
    }

    private void ensureVpnPermissionAndStart() {
        File selected = getSelectedConfigFile();
        if (selected == null) {
            toast("请先导入并选择一个配置文件");
            return;
        }
        Intent intent = VpnService.prepare(this);
        if (intent == null) {
            startVpnWithSelectedConfig();
        } else {
            vpnPermissionLauncher.launch(intent);
        }
    }

    private void startVpnWithSelectedConfig() {
        File selected = getSelectedConfigFile();
        if (selected == null) {
            toast("没有可用配置");
            return;
        }
        Intent intent = new Intent(this, MinoVpnService.class)
                .setAction(MinoVpnService.ACTION_START)
                .putExtra(MinoVpnService.EXTRA_CONFIG_PATH, selected.getAbsolutePath());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        refreshStatus();
    }

    private void stopVpn() {
        Intent intent = new Intent(this, MinoVpnService.class).setAction(MinoVpnService.ACTION_STOP);
        startService(intent);
        refreshStatus();
    }

    private void refreshConfigList() {
        configFiles.clear();
        configFiles.addAll(repository.listConfigs());
        spinnerAdapter.clear();
        for (File file : configFiles) {
            spinnerAdapter.add(file.getName());
        }
        spinnerAdapter.notifyDataSetChanged();

        if (configFiles.isEmpty()) {
            binding.configDetailText.setText("还没有导入配置文件");
            return;
        }

        String selectedName = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SELECTED_CONFIG, configFiles.get(0).getName());
        selectConfig(selectedName);
    }

    private void selectConfig(String fileName) {
        for (int i = 0; i < configFiles.size(); i++) {
            if (configFiles.get(i).getName().equals(fileName)) {
                binding.configSpinner.setSelection(i);
                renderConfigDetail(configFiles.get(i));
                return;
            }
        }
        binding.configSpinner.setSelection(0);
        renderConfigDetail(configFiles.get(0));
    }

    private void renderConfigDetail(File file) {
        try {
            MinoConfig config = repository.readConfig(file);
            binding.configDetailText.setText(config.summary(file.getName()));
        } catch (IOException e) {
            binding.configDetailText.setText("配置读取失败: " + e.getMessage());
        }
    }

    private void refreshStatus() {
        renderStatus(MinoVpnService.isRunning(), MinoVpnService.getActiveConfigName(), null);
    }

    private void renderStatus(boolean running, String configName, String statusText) {
        if (running) {
            String selected = (configName == null || configName.isEmpty()) ? "(未知)" : configName;
            binding.statusText.setText("状态: 运行中\n配置: " + selected);
            binding.startButton.setEnabled(false);
            binding.stopButton.setEnabled(true);
            return;
        }
        String text = (statusText == null || statusText.isEmpty()) ? "未启动" : statusText;
        binding.statusText.setText("状态: " + text);
        binding.startButton.setEnabled(true);
        binding.stopButton.setEnabled(false);
    }

    private File getSelectedConfigFile() {
        int position = binding.configSpinner.getSelectedItemPosition();
        if (position < 0 || position >= configFiles.size()) {
            return null;
        }
        return configFiles.get(position);
    }

    private String resolveDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "mino.yml" : segment;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }
}
