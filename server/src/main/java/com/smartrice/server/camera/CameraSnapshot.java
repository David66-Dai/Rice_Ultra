package com.smartrice.server.camera;

/** 服务端代取到的一帧摄像头画面。 */
public record CameraSnapshot(byte[] bytes, String contentType) {
}
