import assert from 'node:assert/strict'
import test from 'node:test'
import { blobToCaptureFile, canCaptureMonitor, captureFileName } from '../src/lib/camera-capture.ts'

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
})

test('captured blob becomes a jpeg file for diagnosis upload', () => {
  const file = blobToCaptureFile(new Blob(['frame'], { type: 'image/jpeg' }), 'S01-CAM-01.jpg')
  assert.equal(file.name, 'S01-CAM-01.jpg')
  assert.equal(file.type, 'image/jpeg')
})
