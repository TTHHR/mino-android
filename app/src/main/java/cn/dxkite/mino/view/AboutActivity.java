package cn.dxkite.mino.view;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;
import cn.dxkite.mino.R;

public class AboutActivity extends AppCompatActivity {
    @BindView(R.id.wifiImg)
    ImageView wifiImg;
    @BindView(R.id.mobileImg)
    ImageView mobileImg;
    Unbinder mUnbinder;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        mUnbinder = ButterKnife.bind(this);
        mobileImg.setOnClickListener(v->{
            new AlertDialog.Builder(this)
                    .setTitle("need help?")
                    .setMessage("https://github.com/TTHHR/mino-android")
                    .setPositiveButton("view", (dialogInterface, i) -> {
                        Uri uri = Uri.parse("https://github.com/TTHHR/mino-android");
                        Intent it = new Intent(Intent.ACTION_VIEW, uri);
                        startActivity(it);
                    })
                    .show();
        });
        wifiImg.setOnClickListener(v->{
            new AlertDialog.Builder(this)
                    .setTitle("need help?")
                    .setMessage("https://github.com/TTHHR/mino-android")
                    .setPositiveButton("view", (dialogInterface, i) -> {
                        Uri uri = Uri.parse("https://github.com/TTHHR/mino-android");
                        Intent it = new Intent(Intent.ACTION_VIEW, uri);
                        startActivity(it);
                    })
                    .show();
        });
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        mUnbinder.unbind();
    }
}