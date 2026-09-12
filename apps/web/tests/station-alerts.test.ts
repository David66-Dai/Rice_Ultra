import assert from 'node:assert/strict'
import test from 'node:test'
import {
  canDiagnoseStation,
  confidencePercent,
  formatConfidence,
  recognitionCardLevel,
  stationLevelLabel,
  stationPointClass,
  stationVisualLevel,
} from '../src/lib/station-alerts.ts'

test('offline stations cannot open recognition', () => {
  assert.equal(canDiagnoseStation('S01'), true)
  assert.equal(canDiagnoseStation('S02'), false)
  assert.equal(canDiagnoseStation('S10'), false)
  assert.equal(recognitionCardLevel(false, 'red'), 'offline')
  assert.equal(recognitionCardLevel(true, 'red'), 'danger')
})

test('station colors follow red over yellow over online/offline green', () => {
  assert.equal(stationVisualLevel(true, 'green'), 'normal')
  assert.equal(stationVisualLevel(false, 'green'), 'offline')
  assert.equal(stationVisualLevel(true, 'yellow'), 'attention')
  assert.equal(stationVisualLevel(false, 'yellow'), 'attention')
  assert.equal(stationVisualLevel(true, 'red'), 'danger')
  assert.equal(stationVisualLevel(false, 'red'), 'danger')
  assert.equal(stationLevelLabel(true, 'green'), '在线')
  assert.equal(stationLevelLabel(false, 'green'), '离线')
  assert.equal(stationLevelLabel(true, 'yellow'), '黄色预警')
  assert.equal(stationLevelLabel(false, 'red'), '红色告警')
})

test('homepage point class overlays alert color on online and offline stations', () => {
  assert.equal(stationPointClass(true, 'green', false), 'station-point is-online')
  assert.equal(stationPointClass(false, 'green', true), 'station-point is-offline is-selected')
  assert.equal(stationPointClass(true, 'yellow', false), 'station-point is-online is-alert-yellow')
  assert.equal(stationPointClass(false, 'red', true), 'station-point is-offline is-alert-red is-selected')
  assert.equal(recognitionCardLevel(true, 'green'), 'normal')
  assert.equal(recognitionCardLevel(true, 'yellow'), 'attention')
  assert.equal(recognitionCardLevel(true, 'red'), 'danger')
})

test('confidence formatting treats both 0-1 and percent values as percents', () => {
  assert.equal(formatConfidence(0.954), '95.4%')
  assert.equal(formatConfidence(96.2), '96.2%')
  assert.equal(formatConfidence(null), '')
  assert.equal(confidencePercent(0.5), 50)
  assert.equal(confidencePercent(120), 100)
})
