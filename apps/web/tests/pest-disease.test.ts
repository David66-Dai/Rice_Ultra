import assert from 'node:assert/strict'
import test from 'node:test'
import type { PestDiseaseSummary } from '@smart-rice-security/shared'
import {
  MOCK_END_DATE,
  MOCK_START_DATE,
  PEST_DISEASE_CATALOG,
  emptyPestDisease,
  growthPhaseLabel,
  hasMockPestDisease,
  mockPestDisease,
  pestAlertLevel,
  pestDiseaseOverallLevel,
  pestDiseaseSourceLabel,
  resolvePestDisease,
} from '../src/lib/pest-disease.ts'

const KEYS = PEST_DISEASE_CATALOG.map(entry => entry.key)

function inspection(stationId = 'S01', date = '2026-09-12'): PestDiseaseSummary {
  return {
    available: true, source: 'inspection_diagnosis', sourceTable: 'inspection_diagnosis', stationId,
    referenceDate: date, lastDiagnosedAt: '2026-09-12T06:59:56Z', recognitionCount: 5,
    items: PEST_DISEASE_CATALOG.map(entry => ({
      key: entry.key, category: entry.category, label: entry.label, unit: entry.unit,
      value: 0, alertLevel: 'green' as const,
    })),
    notes: [],
  }
}

function days(from: string, to: string): string[] {
  const result: string[] = []
  for (let day = new Date(`${from}T00:00:00Z`); day <= new Date(`${to}T00:00:00Z`); day.setUTCDate(day.getUTCDate() + 1)) {
    result.push(day.toISOString().slice(0, 10))
  }
  return result
}

test('the catalogue is the fixed three diseases and three pests, with their counting units', () => {
  assert.deepEqual(KEYS, ['bacterial_leaf_blight', 'brown_spot', 'tungro_virus',
    'rice_planthopper', 'striped_stem_borer', 'rice_leaf_roller'])
  assert.deepEqual(PEST_DISEASE_CATALOG.filter(entry => entry.category === 'disease').map(entry => entry.unit),
    ['次', '次', '次'])
  assert.deepEqual(PEST_DISEASE_CATALOG.filter(entry => entry.category === 'pest').map(entry => entry.unit),
    ['只', '只', '只'])
})

test('alert colours follow the field inspection thresholds and never colour a missing value', () => {
  assert.equal(pestAlertLevel('disease', 0), 'green')
  assert.equal(pestAlertLevel('disease', 1), 'red')
  assert.equal(pestAlertLevel('disease', 9), 'red')
  assert.equal(pestAlertLevel('pest', 0), 'green')
  assert.equal(pestAlertLevel('pest', 1), 'yellow')
  assert.equal(pestAlertLevel('pest', 2), 'red')
  for (const missing of [null, Number.NaN, Number.POSITIVE_INFINITY]) {
    assert.equal(pestAlertLevel('disease', missing as number | null), 'green')
    assert.equal(pestAlertLevel('pest', missing as number | null), 'green')
  }
})

test('sample data covers exactly 2026-05-01 to 2026-09-11 and nothing outside it', () => {
  assert.equal(MOCK_START_DATE, '2026-05-01')
  assert.equal(MOCK_END_DATE, '2026-09-11')
  assert.equal(hasMockPestDisease('2026-04-30'), false)
  assert.equal(hasMockPestDisease('2026-05-01'), true)
  assert.equal(hasMockPestDisease('2026-09-11'), true)
  assert.equal(hasMockPestDisease('2026-09-12'), false)
  assert.equal(hasMockPestDisease('2026-02-30'), false)
  assert.equal(hasMockPestDisease('not-a-date'), false)
  assert.equal(mockPestDisease('S01', '2026-04-30'), null)
  assert.equal(mockPestDisease('S11', '2026-06-01'), null)
  assert.equal(mockPestDisease('S01 OR 1=1', '2026-06-01'), null)
})

test('every sampled day returns the whole catalogue in order with a level that matches its value', () => {
  for (const date of ['2026-05-01', '2026-06-15', '2026-08-02', '2026-09-11']) {
    const summary = mockPestDisease('S03', date)!
    assert.equal(summary.source, 'mock')
    assert.equal(summary.available, true)
    assert.equal(summary.referenceDate, date)
    assert.equal(summary.stationId, 'S03')
    assert.deepEqual(summary.items.map(item => item.key), KEYS)
    for (const item of summary.items) {
      assert.ok(Number.isInteger(item.value) && item.value! >= 0, `${item.key} ${item.value}`)
      assert.equal(item.alertLevel, pestAlertLevel(item.category, item.value))
    }
    assert.ok(summary.recognitionCount > 0)
    assert.ok(summary.notes.some(note => note.includes('模拟数据')))
  }
})

test('the same station and day always produce the same figures, and stations differ from each other', () => {
  const first = mockPestDisease('S01', '2026-07-30')!
  const again = mockPestDisease('S01', '2026-07-30')!
  assert.deepEqual(first.items, again.items)
  assert.equal(first.recognitionCount, again.recognitionCount)
  const everyStation = Array.from({ length: 10 }, (_, index) => `S${String(index + 1).padStart(2, '0')}`)
    .map(station => JSON.stringify(mockPestDisease(station, '2026-07-30')!.items))
  assert.ok(new Set(everyStation).size > 1, 'stations must not all report identical figures')
})

test('phase boundaries match farm.env_daily, and pressure rises into heading then falls back', () => {
  assert.equal(growthPhaseLabel('2026-05-01'), '育秧期')
  assert.equal(growthPhaseLabel('2026-05-20'), '育秧期')
  assert.equal(growthPhaseLabel('2026-05-21'), '分蘖期')
  assert.equal(growthPhaseLabel('2026-06-30'), '分蘖期')
  assert.equal(growthPhaseLabel('2026-07-01'), '拔节期')
  assert.equal(growthPhaseLabel('2026-07-16'), '孕穗期')
  assert.equal(growthPhaseLabel('2026-07-26'), '抽穗期')
  assert.equal(growthPhaseLabel('2026-08-11'), '灌浆期')
  assert.equal(growthPhaseLabel('2026-09-11'), '成熟期')

  const total = (from: string, to: string) => days(from, to)
    .flatMap(date => Array.from({ length: 10 }, (_, index) => `S${String(index + 1).padStart(2, '0')}`)
      .map(station => mockPestDisease(station, date)!))
    .reduce((sum, summary) => sum + summary.items.reduce((row, item) => row + (item.value ?? 0), 0), 0)
  // Average catalogue total per station per day, so phases of different lengths compare directly.
  const perDay = (from: string, to: string) => total(from, to) / (days(from, to).length * 10)
  const nursery = perDay('2026-05-01', '2026-05-20')
  const tillering = perDay('2026-05-21', '2026-06-30')
  const heading = perDay('2026-07-26', '2026-08-10')
  const filling = perDay('2026-08-11', '2026-09-10')
  assert.ok(nursery < tillering, `nursery ${nursery} < tillering ${tillering}`)
  assert.ok(tillering < heading, `tillering ${tillering} < heading ${heading}`)
  assert.ok(filling < heading, `filling ${filling} < heading ${heading}`)
  assert.ok(nursery < 0.5, `the nursery stage should stay almost clean, got ${nursery}`)
  assert.ok(heading > 2, `the heading peak should be clearly busy, got ${heading}`)
})

test('every colour actually appears across the sampled season, so the card is exercised', () => {
  const levels = new Set<string>()
  for (const date of days(MOCK_START_DATE, MOCK_END_DATE)) {
    for (const item of mockPestDisease('S01', date)!.items) levels.add(item.alertLevel)
  }
  assert.deepEqual([...levels].sort(), ['green', 'red', 'yellow'])
})

test('real same-day figures always win over the sample data', () => {
  // A same-day summary is kept even if the date happens to fall inside the sample range.
  const real = inspection('S01', '2026-08-02')
  assert.equal(resolvePestDisease(real, 'S01', '2026-08-02'), real)
  const sampled = resolvePestDisease(null, 'S01', '2026-08-02')
  assert.equal(sampled?.source, 'mock')
})

test('a day outside the sample range keeps whatever the backend returned', () => {
  const empty = emptyPestDisease('S01', '2026-09-12', ['该日期不是当天'])
  assert.equal(resolvePestDisease(empty, 'S01', '2026-09-12'), empty)
  assert.equal(resolvePestDisease(null, 'S01', '2026-04-01'), null)
  assert.equal(resolvePestDisease(undefined, 'S01', '2026-04-01'), null)
  assert.deepEqual(empty.items.map(item => item.value), [null, null, null, null, null, null])
  assert.deepEqual(empty.items.map(item => item.key), KEYS)
})

test('the card badge reports the worst level present, and labels say where the data came from', () => {
  assert.equal(pestDiseaseOverallLevel(null), 'green')
  assert.equal(pestDiseaseOverallLevel(emptyPestDisease('S01', '2026-09-12', [])), 'green')
  const base = inspection()
  assert.equal(pestDiseaseOverallLevel(base), 'green')
  const withYellow = { ...base, items: base.items.map((item, index) => index === 3
    ? { ...item, value: 1, alertLevel: 'yellow' as const } : item) }
  assert.equal(pestDiseaseOverallLevel(withYellow), 'yellow')
  const withRed = { ...withYellow, items: withYellow.items.map((item, index) => index === 0
    ? { ...item, value: 2, alertLevel: 'red' as const } : item) }
  assert.equal(pestDiseaseOverallLevel(withRed), 'red')

  assert.equal(pestDiseaseSourceLabel(base), '当天巡检记录')
  assert.equal(pestDiseaseSourceLabel({ ...base, available: false }), '当天暂无识别')
  assert.equal(pestDiseaseSourceLabel(mockPestDisease('S01', '2026-06-01')), '往期模拟数据')
  assert.equal(pestDiseaseSourceLabel(emptyPestDisease('S01', '2026-04-01', [])), '暂无数据')
  assert.equal(pestDiseaseSourceLabel(null), '暂无数据')
})
