import { useEffect } from 'react'

type WebkitWindow = Window & typeof globalThis & {
  webkitAudioContext?: typeof AudioContext
}

export function useButtonClickSound() {
  useEffect(() => {
    let audioContext: AudioContext | null = null

    function playClickSound() {
      const AudioContextConstructor = window.AudioContext
        ?? (window as WebkitWindow).webkitAudioContext
      if (!AudioContextConstructor) return

      audioContext ??= new AudioContextConstructor()
      const context = audioContext
      if (context.state === 'suspended') void context.resume()

      const startedAt = context.currentTime
      const masterGain = context.createGain()
      const primaryTone = context.createOscillator()
      const detailTone = context.createOscillator()

      masterGain.gain.setValueAtTime(0.0001, startedAt)
      masterGain.gain.exponentialRampToValueAtTime(0.13, startedAt + 0.004)
      masterGain.gain.exponentialRampToValueAtTime(0.0001, startedAt + 0.085)

      primaryTone.type = 'sine'
      primaryTone.frequency.setValueAtTime(620, startedAt)
      primaryTone.frequency.exponentialRampToValueAtTime(980, startedAt + 0.055)

      detailTone.type = 'triangle'
      detailTone.frequency.setValueAtTime(1240, startedAt)
      detailTone.frequency.exponentialRampToValueAtTime(1540, startedAt + 0.04)

      const detailGain = context.createGain()
      detailGain.gain.setValueAtTime(0.32, startedAt)
      detailGain.gain.exponentialRampToValueAtTime(0.0001, startedAt + 0.045)

      primaryTone.connect(masterGain)
      detailTone.connect(detailGain)
      detailGain.connect(masterGain)
      masterGain.connect(context.destination)

      primaryTone.start(startedAt)
      detailTone.start(startedAt)
      primaryTone.stop(startedAt + 0.075)
      detailTone.stop(startedAt + 0.05)
    }

    function handleClick(event: MouseEvent) {
      if (!(event.target instanceof Element)) return
      const button = event.target.closest<HTMLElement>('button, [role="button"]')
      if (!button || button.matches(':disabled') || button.getAttribute('aria-disabled') === 'true') return
      playClickSound()
    }

    document.addEventListener('click', handleClick)
    return () => {
      document.removeEventListener('click', handleClick)
      if (audioContext && audioContext.state !== 'closed') void audioContext.close()
    }
  }, [])
}
