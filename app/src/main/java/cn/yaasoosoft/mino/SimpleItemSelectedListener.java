package cn.yaasoosoft.mino;

import android.view.View;
import android.widget.AdapterView;

public class SimpleItemSelectedListener implements AdapterView.OnItemSelectedListener {
    public interface OnItemSelected {
        void onSelected(int position);
    }

    private final OnItemSelected listener;

    public SimpleItemSelectedListener(OnItemSelected listener) {
        this.listener = listener;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        listener.onSelected(position);
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }
}
