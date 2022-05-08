package cn.dxkite.mino.view;


import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;
import cn.dxkite.mino.R;
import cn.dxkite.mino.entity.MinoConfig;
import cn.dxkite.mino.exception.MinoException;
import cn.dxkite.mino.presenter.MainPresenter;
import cn.dxkite.mino.view.inter.MainInterface;

public class MainActivity extends AppCompatActivity implements MainInterface {
    ActivityResultLauncher<Intent> requestActivity;
    @BindView(R.id.aboutButton)
    Button aboutButton;
    @BindView(R.id.startButton)
    Button startButton;
    @BindView(R.id.stopButton)
    Button stopButton;
    @BindView(R.id.importButton)
    Button importButton;
    @BindView(R.id.showConfigButton)
    Button showConfigButton;
    @BindView(R.id.configText)
    TextView configText;
    @BindView(R.id.titleBar)
    LinearLayout titleBar;

    Unbinder mUnbinder;

    MainPresenter mainPresenter;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mUnbinder = ButterKnife.bind(this);
        requestActivity = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK) {
                Intent uri = result.getData();
                if (uri != null) {
                    try {
                        mainPresenter.loadMinoConfig(uri.getData());
                        showMinoConfig(mainPresenter.getMinoConfig(),true);
                    } catch (MinoException e) {
                        showError(e.getMessage());
                    }
                }
            }
        });
        initView();
        mainPresenter=new MainPresenter(this);
    }
    // 打开文件管理器选择文件
    private void openFileManager() {
        // 打开文件管理器选择文件
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");//无类型限制
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        requestActivity.launch(intent);
    }

    private void initView()
    {
        startButton.setOnClickListener(v->{
            mainPresenter.start();
        });
        stopButton.setOnClickListener(v->{
            mainPresenter.stop();
        });
        showConfigButton.setOnClickListener(view -> {
            MinoConfig config=mainPresenter.getMinoConfig();
            showMinoConfig(config,true);
        });
        importButton.setOnClickListener(v->{
            openFileManager();
        });
        aboutButton.setOnClickListener(v->{
            startActivity(new Intent(this,AboutActivity.class));
        });

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mUnbinder.unbind();
        mainPresenter.stop();
    }
    @Override
    public void showError(String text)
    {
        runOnUiThread(()->{
            new AlertDialog.Builder(this)
                    .setTitle("Message")
                    .setMessage(text)
                    .setPositiveButton("done",null)
                    .show();
        });
    }

    private View configlayout=null;
    private EditText encoderText,addressText,encodeKeyText,upstreamText;
    @Override
    public void showMinoConfig(MinoConfig config,boolean editable) {
        if(config==null) {
            runOnUiThread(()->{
                Toast.makeText(this,"config null",Toast.LENGTH_LONG).show();
            });
            return;
        }
        runOnUiThread(()->{
            if(configlayout==null) {
                LayoutInflater inflater = LayoutInflater.from(this);
                configlayout= inflater.inflate(R.layout.config_layout, null);
                encoderText=configlayout.findViewById(R.id.encoder);
                addressText=configlayout.findViewById(R.id.localAddress);
                encodeKeyText=configlayout.findViewById(R.id.encoderKey);
                upstreamText=configlayout.findViewById(R.id.upstream);
            }

            encoderText.setText(config.getEncoder());

            addressText.setText(config.getAddress());

            encodeKeyText.setText(config.getMino_encoder_key());

            upstreamText.setText(config.getUpstream());

            if(!editable)
            {
                encoderText.setFocusable(false);
                encoderText.setFocusableInTouchMode(false);

                encodeKeyText.setFocusable(false);
                encodeKeyText.setFocusableInTouchMode(false);

                upstreamText.setFocusable(false);
                upstreamText.setFocusableInTouchMode(false);

                addressText.setFocusable(false);
                addressText.setFocusableInTouchMode(false);

            }
            new AlertDialog.Builder(this)
                    .setTitle("Config")
                    .setView(configlayout)
                    .setPositiveButton("done",null)
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialogInterface) {
                            ((ViewGroup)configlayout.getParent()).removeView(configlayout);
                            dialogInterface.dismiss();
                        }
                    })

                    .show();
        });
    }
}