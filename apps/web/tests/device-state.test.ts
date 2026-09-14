import assert from 'node:assert/strict'
import test from 'node:test'
import { DEVICE_LABELS, deviceLabel, linkageHint, normalizeDeviceMessage } from '../src/lib/devices.ts'

test('device labels use spray instead of pump water', () => {
  assert.equal(DEVICE_LABELS.pump, '喷药')
  assert.equal(DEVICE_LABELS.lamp, '驱虫灯')
  assert.equal(deviceLabel('pump'), '喷药')
  assert.equal(deviceLabel('lamp'), '驱虫灯')
})

test('legacy notifications are displayed with the spray name', () => {
  assert.equal(normalizeDeviceMessage('系统用户开启智能灌溉水泵功能'), '系统用户开启智能喷药功能')
  assert.equal(normalizeDeviceMessage('已关闭水泵'), '已关闭喷药')
  assert.equal(normalizeDeviceMessage('已开启驱虫灯'), '已开启驱虫灯')
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

test('pending diagnosis linkage explains AstrBot message confirmation without claiming the device opened', () => {
  const id = '00000000-0000-4000-8000-000000000099'
  assert.equal(linkageHint('leaf', null, null, true, id, 'PENDING'), `AstrBot 消息告警发送中 · 确认编号 ${id}`)
  assert.equal(linkageHint('pest', null, null, true, id, 'SENT'), `等待 AstrBot 消息确认 · 确认编号 ${id}`)
  assert.equal(linkageHint('leaf', null, null, true, id, 'FAILED'), `AstrBot 消息告警发送失败，设备不会开启 · 确认编号 ${id}`)
})

test('a partially delivered alert still waits for a session that actually received it', () => {
  const id = '00000000-0000-4000-8000-000000000100'
  assert.equal(
    linkageHint('leaf', null, null, true, id, 'PARTIAL'),
    `等待 AstrBot 消息确认（部分会话未送达） · 确认编号 ${id}`,
  )
  assert.equal(linkageHint('leaf', 'pump', null, true, id, 'PARTIAL'), `等待 AstrBot 消息确认（部分会话未送达） · 确认编号 ${id}`)
})
