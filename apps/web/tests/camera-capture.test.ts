import assert from 'node:assert/strict'
import test from 'node:test'
import { blobToCaptureFile, canCaptureMonitor, captureFileName, snapshotProxyPath } from '../src/lib/camera-capture.ts'

test('only the live online monitor can take a photo', () => {
  assert.equal(canCaptureMonitor(true, true, false), true)
  assert.equal(canCaptureMonitor(false, true, false), false)
  assert.equal(canCaptureMonitor(true, false, false), false)
  assert.equal(canCaptureMonitor(true, true, true), false)
})

test('capture filename keeps station camera and timestamp', () => {
  assert.equal(
    captureFileName('S01', 1, new Date(2026, 8, 11, 10, 7, 8)),
    'S01-CAM-01-20260911-100708.jpg',
  )
  assert.equal(
    captureFileName('S05', 2, new Date(2026, 8, 11, 10, 7, 8), 'NET'),
    'S05-NET-02-20260911-100708.jpg',
  )
})

test('captured blob becomes a jpeg file for diagnosis upload', () => {
  const file = blobToCaptureFile(new Blob(['frame'], { type: 'image/jpeg' }), 'S01-CAM-01.jpg')
  assert.equal(file.name, 'S01-CAM-01.jpg')
  assert.equal(file.type, 'image/jpeg')
})

test('cross-origin cameras fall back to the server snapshot proxy', () => {
  assert.equal(
    snapshotProxyPath('http://admin:123456@192.168.1.64/cgi-bin/snapshot.cgi?ch=1'),
    '/api/inspection/camera-snapshot?url=http%3A%2F%2Fadmin%3A123456%40192.168.1.64%2Fcgi-bin%2Fsnapshot.cgi%3Fch%3D1',
  )
})
