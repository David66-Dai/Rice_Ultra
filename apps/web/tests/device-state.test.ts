import assert from 'node:assert/strict'
import test from 'node:test'
import { DEVICE_LABELS, deviceLabel, linkageHint } from '../src/lib/devices.ts'

test('device labels use spray instead of pump water', () => {
  assert.equal(DEVICE_LABELS.pump, '喷药')
  assert.equal(DEVICE_LABELS.lamp, '驱虫灯')
  assert.equal(deviceLabel('pump'), '喷药')
  assert.equal(deviceLabel('lamp'), '驱虫灯')
})

test('leaf red links spray and pest red links lamp', () => {
  assert.equal(linkageHint('leaf', 'pump', null), '已联动开启喷药')
  assert.equal(linkageHint('pest', 'lamp', null), '已联动开启驱虫灯')
  assert.equal(linkageHint('leaf', null, null), null)
  assert.equal(linkageHint('pest', null, null), null)
})

test('serial failure keeps diagnosis copy but reports the command error', () => {
  assert.equal(linkageHint('leaf', null, '串口 COM4 当前未连接'), '喷药指令未发出：串口 COM4 当前未连接')
  assert.equal(linkageHint('pest', null, '串口 COM4 当前未连接'), '驱虫灯指令未发出：串口 COM4 当前未连接')
})
