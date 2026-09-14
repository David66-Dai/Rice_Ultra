import assert from 'node:assert/strict'
import test from 'node:test'
import { buildHyperspectralReading, spectrumToPolyline } from '../src/lib/hyperspectral.ts'

test('spectrum polyline maps wavelengths across the chart', () => {
  const line = spectrumToPolyline([400, 700, 1000], [0.1, 0.5, 0.2], 240, 40)
  assert.match(line, /^0\.0,/)
  assert.match(line, /240\.0,/)
})

test('uploaded cube replaces the waiting copy', () => {
  const waiting = buildHyperspectralReading(true)
  assert.equal(waiting.label, '等待上传立方体')
  const ready = buildHyperspectralReading(true, {
    labelZh: '褐斑病',
    confidence: 0.885,
    hasDamage: true,
    spectrum: {
      wavelengths: [400, 1000],
      reflectance: [0.05, 0.5],
      ndvi: 0.88,
      ndre: 0.26,
    },
  })
  assert.equal(ready.label, '褐斑病')
  assert.equal(ready.confidence, 88.5)
  assert.match(ready.detail, /NDVI/)
})
