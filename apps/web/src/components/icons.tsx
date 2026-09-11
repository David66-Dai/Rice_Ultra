import type { SVGProps } from 'react'

type IconProps = SVGProps<SVGSVGElement> & { size?: number }

function base({ size = 18, ...rest }: IconProps) {
  return {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
    focusable: 'false' as const,
    ...rest,
  }
}

export function IconUser(props: IconProps) {
  return (
    <svg {...base(props)}>
      <circle cx="12" cy="8.5" r="3.6" />
      <path d="M4.8 19.5c1.2-3.3 3.9-5 7.2-5s6 1.7 7.2 5" />
    </svg>
  )
}

export function IconLock(props: IconProps) {
  return (
    <svg {...base(props)}>
      <rect x="5" y="10.5" width="14" height="9.5" rx="2.2" />
      <path d="M8.2 10.5V8a3.8 3.8 0 0 1 7.6 0v2.5" />
      <circle cx="12" cy="15.3" r="1.1" fill="currentColor" stroke="none" />
    </svg>
  )
}

export function IconEye(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M2.8 12s3.4-6 9.2-6 9.2 6 9.2 6-3.4 6-9.2 6-9.2-6-9.2-6Z" />
      <circle cx="12" cy="12" r="2.8" />
    </svg>
  )
}

export function IconEyeOff(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M3.5 3.5l17 17" />
      <path d="M10.3 6.3A9.7 9.7 0 0 1 12 6c5.8 0 9.2 6 9.2 6a15 15 0 0 1-3.2 3.7" />
      <path d="M6.3 7.7A14.7 14.7 0 0 0 2.8 12s3.4 6 9.2 6c1.4 0 2.6-.3 3.7-.8" />
      <path d="M9.9 9.9a2.8 2.8 0 0 0 4.1 4.1" />
    </svg>
  )
}

export function IconCheck(props: IconProps) {
  return (
    <svg {...base({ size: 12, strokeWidth: 3, ...props })}>
      <path d="M5 12.5l4.2 4L19 7.5" />
    </svg>
  )
}

export function IconAlert(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M12 3.5 21 19.5H3L12 3.5Z" />
      <path d="M12 9.5v4.5" />
      <circle cx="12" cy="16.8" r=".9" fill="currentColor" stroke="none" />
    </svg>
  )
}

export function IconArrowRight(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M5 12h13" />
      <path d="m14 7 5 5-5 5" />
    </svg>
  )
}

export function IconLogout(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M14 5.5h4a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5h-4" />
      <path d="M10 8.5 6.5 12l3.5 3.5" />
      <path d="M6.5 12h9" />
    </svg>
  )
}
