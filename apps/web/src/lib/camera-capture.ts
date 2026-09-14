export function canCaptureMonitor(online: boolean, cameraLive: boolean, busy: boolean) {
  return online && cameraLive && !busy
}

export function captureFileName(stationId: string, cameraIndex: number, at = new Date(), source = 'CAM') {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${stationId}-${source}-${pad(cameraIndex)}-${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}-${pad(at.getHours())}${pad(at.getMinutes())}${pad(at.getSeconds())}.jpg`
}

export function blobToCaptureFile(blob: Blob, filename: string) {
  return new File([blob], filename, { type: blob.type || 'image/jpeg' })
}

async function drawToCaptureFile(
  source: CanvasImageSource,
  width: number,
  height: number,
  filename: string,
): Promise<File> {
  if (!width || !height) throw new Error('画面尚未就绪，请稍后再拍')
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('当前浏览器无法截取画面')
  context.drawImage(source, 0, 0)
  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((next) => {
      if (!next) reject(new Error('截取画面失败'))
      else resolve(next)
    }, 'image/jpeg', 0.92)
  })
  return blobToCaptureFile(blob, filename)
}

export function captureVideoFrame(video: HTMLVideoElement, filename: string): Promise<File> {
  return drawToCaptureFile(video, video.videoWidth, video.videoHeight, filename)
}

/** 摄像头未开放跨域时抛出，调用方可改走服务端代理取帧。 */
export class CrossOriginCaptureError extends Error {
  constructor() {
    super('该网络摄像头未开放跨域访问，浏览器无法直接抓拍')
    this.name = 'CrossOriginCaptureError'
  }
}

// A network camera frame lives in an <img>; without CORS headers the canvas is tainted
// and toBlob throws SecurityError.
export async function captureImageFrame(image: HTMLImageElement, filename: string): Promise<File> {
  try {
    return await drawToCaptureFile(image, image.naturalWidth, image.naturalHeight, filename)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'SecurityError') throw new CrossOriginCaptureError()
    throw error
  }
}

/** 服务端代取一帧的接口地址，摄像头不支持跨域时使用。 */
export function snapshotProxyPath(snapshotUrl: string) {
  return `/api/inspection/camera-snapshot?url=${encodeURIComponent(snapshotUrl)}`
}
