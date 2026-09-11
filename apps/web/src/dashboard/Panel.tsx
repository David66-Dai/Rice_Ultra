import type { ReactNode } from 'react'

export function Panel({
  title,
  extra,
  children,
  className = '',
}: {
  title: string
  extra?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={`panel ${className}`}>
      <header className="panel__head">
        <h3>{title}</h3>
        {extra}
      </header>
      <div className="panel__body">{children}</div>
    </section>
  )
}
