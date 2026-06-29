export type ConfirmSpec = {
  title: string
  message?: string
  confirmLabel: string
  cancelLabel?: string
  danger?: boolean
  onConfirm: () => void
  onCancel: () => void // must close or transition the dialog (handlers own dialog state)
}

export function ConfirmDialog({ spec }: { spec: ConfirmSpec }) {
  return (
    <div className="modal-scrim" onClick={spec.onCancel}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-title">{spec.title}</div>
        {spec.message && <div className="modal-msg">{spec.message}</div>}
        <div className="modal-actions">
          <button className="modal-btn" onClick={spec.onCancel}>{spec.cancelLabel ?? 'Cancel'}</button>
          <button className={'modal-btn ' + (spec.danger ? 'danger' : 'primary')} onClick={spec.onConfirm}>{spec.confirmLabel}</button>
        </div>
      </div>
    </div>
  )
}
