export function canCaptureMonitor(online: boolean, cameraLive: boolean, busy: boolean) {
  return online && cameraLive && !busy
}

export function captureFileName(stationId: string, cameraIndex: number, at = new Date()) {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${stationId}-CAM-${pad(cameraIndex)}-${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}-${pad(at.getHours())}${pad(at.getMinutes())}${pad(at.getSeconds())}.jpg`
}

export function blobToCaptureFile(blob: Blob, filename: string) {
  return new File([blob], filename, { type: blob.type || 'image/jpeg' })
}

export async function captureVideoFrame(video: HTMLVideoElement, filename: string): Promise<File> {
  if (!video.videoWidth || !video.videoHeight) {
    throw new Error('画面尚未就绪，请稍后再拍')
  }
  const canvas = document.createElement('canvas')
  canvas.width = video.videoWidth
  canvas.height = video.videoHeight
  const context = canvas.getContext('2d')
  if (!context) throw new Error('当前浏览器无法截取画面')
  context.drawImage(video, 0, 0)
  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((next) => {
      if (!next) reject(new Error('截取画面失败'))
      else resolve(next)
    }, 'image/jpeg', 0.92)
  })
  return blobToCaptureFile(blob, filename)
}
