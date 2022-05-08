package cn.dxkite.mino.view.inter;

import cn.dxkite.mino.entity.MinoConfig;

public interface MainInterface {
    void showError(String text);
    void showMinoConfig(MinoConfig config,boolean editable);
}
