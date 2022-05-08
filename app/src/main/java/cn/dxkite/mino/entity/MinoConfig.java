package cn.dxkite.mino.entity;

public class MinoConfig {
    //监听地址
    private String address;
    //上游链接
    private String upstream;
    //加密方式
    private String encoder;
    //加密KEY
    private String mino_encoder_key;

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getUpstream() {
        return upstream;
    }

    public void setUpstream(String upstream) {
        this.upstream = upstream;
    }

    public String getEncoder() {
        return encoder;
    }

    public void setEncoder(String encoder) {
        this.encoder = encoder;
    }

    public String getMino_encoder_key() {
        return mino_encoder_key;
    }

    public void setMino_encoder_key(String mino_encoder_key) {
        this.mino_encoder_key = mino_encoder_key;
    }
}
