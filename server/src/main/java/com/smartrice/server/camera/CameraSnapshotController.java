package com.smartrice.server.camera;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inspection")
public class CameraSnapshotController {

	private final CameraSnapshotService snapshots;

	public CameraSnapshotController(CameraSnapshotService snapshots) {
		this.snapshots = snapshots;
	}

	/** 代取网络摄像头的一帧画面，让前端可以抓拍不支持跨域的摄像头。 */
	@GetMapping("/camera-snapshot")
	public ResponseEntity<byte[]> cameraSnapshot(@RequestParam String url) {
		CameraSnapshot snapshot = snapshots.fetch(url);
		MediaType type;
		try {
			type = MediaType.parseMediaType(snapshot.contentType());
		}
		catch (RuntimeException ex) {
			type = MediaType.IMAGE_JPEG;
		}
		return ResponseEntity.ok()
			.contentType(type)
			.cacheControl(CacheControl.noStore())
			.body(snapshot.bytes());
	}
}
