package com.kunzisoft.keepass.activities.fragments

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.adapters.EntryAttachmentsItemsAdapter
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.element.Attachment
import com.kunzisoft.keepass.database.element.template.TemplateField
import com.kunzisoft.keepass.database.helper.getLocalizedName
import com.kunzisoft.keepass.model.EntryAttachmentState
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.StreamDirection
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.timeout.ClipboardHelper
import com.kunzisoft.keepass.utils.TimeUtil.getDateTimeString
import com.kunzisoft.keepass.utils.UuidUtil
import com.kunzisoft.keepass.view.TemplateView
import com.kunzisoft.keepass.view.hideByFading
import com.kunzisoft.keepass.view.showByFading
import com.kunzisoft.keepass.viewmodels.EntryViewModel

// larry: use toasts for password send msg
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import com.kunzisoft.keepass.receivers.BleHub

class EntryFragment: DatabaseFragment() {

    private lateinit var rootView: View
    private lateinit var mainSection: View
    private lateinit var advancedSection: View

    private lateinit var templateView: TemplateView

    private lateinit var creationDateView: TextView
    private lateinit var modificationDateView: TextView

    private lateinit var attachmentsContainerView: View
    private lateinit var attachmentsListView: RecyclerView
    private var attachmentsAdapter: EntryAttachmentsItemsAdapter? = null

    private lateinit var customDataView: TextView

    private lateinit var uuidContainerView: View
    private lateinit var uuidReferenceView: TextView

    private var mClipboardHelper: ClipboardHelper? = null

    private val mEntryViewModel: EntryViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        return inflater.inflate(R.layout.fragment_entry, container, false)
    }
    
    override fun onViewCreated(view: View,
                               savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        context?.let { context ->
            mClipboardHelper = ClipboardHelper(context)
        }

        rootView = view
        // Hide only the first time
        if (savedInstanceState == null) {
            view.isVisible = false
        }

        mainSection = view.findViewById(R.id.entry_section_main)
        advancedSection = view.findViewById(R.id.entry_section_advanced)

        templateView = view.findViewById(R.id.entry_template)
        loadTemplateSettings()

		// Observe global BLE connection; dim/lock the send button when not ready.
		// (We also check prefs so the button doesn't look “busy” if output device isn’t enabled/selected.)
		BleHub.connected.observe(viewLifecycleOwner) { connected ->
			val ctx = requireContext()
			val useExt = PreferencesUtil.useExternalKeyboardDevice(ctx)
			val hasDevice = !PreferencesUtil.getOutputDeviceId(ctx).isNullOrBlank()

			// If output device is not enabled/selected, keep it looking “not ready”.
			// Otherwise enable only when actually connected.
			val shouldEnable = useExt && hasDevice && connected

			// Using your existing affordance:
			// setSendInProgress(false)  -> enabled/idle look
			// setSendInProgress(true)   -> disabled/busy look
			templateView.setSendInProgress(!shouldEnable)
		}

        attachmentsContainerView = view.findViewById(R.id.entry_attachments_container)
        attachmentsListView = view.findViewById(R.id.entry_attachments_list)
        attachmentsListView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
        }

        creationDateView = view.findViewById(R.id.entry_created)
        modificationDateView = view.findViewById(R.id.entry_modified)

        // TODO Custom data
        // customDataView = view.findViewById(R.id.entry_custom_data)

        uuidContainerView = view.findViewById(R.id.entry_UUID_container)
        uuidContainerView.apply {
            visibility = if (PreferencesUtil.showUUID(context)) View.VISIBLE else View.GONE
        }
        uuidReferenceView = view.findViewById(R.id.entry_UUID_reference)

        mEntryViewModel.entryInfoHistory.observe(viewLifecycleOwner) { entryInfoHistory ->
            if (entryInfoHistory != null) {
                templateView.setTemplate(entryInfoHistory.template)
                assignEntryInfo(entryInfoHistory.entryInfo)
                // Smooth appearing
                rootView.showByFading()
                resetAppTimeoutWhenViewFocusedOrChanged(rootView)
            }
        }

        mEntryViewModel.onAttachmentAction.observe(viewLifecycleOwner) { entryAttachmentState ->
            entryAttachmentState?.let {
                if (it.streamDirection != StreamDirection.UPLOAD) {
                    putAttachment(it)
                }
            }
        }

        mEntryViewModel.sectionSelected.observe(viewLifecycleOwner) { entrySection ->
            when (entrySection ?: EntryViewModel.EntrySection.MAIN) {
                EntryViewModel.EntrySection.MAIN -> {
                    mainSection.showByFading()
                    advancedSection.hideByFading()
                }
                EntryViewModel.EntrySection.ADVANCED -> {
                    mainSection.hideByFading()
                    advancedSection.showByFading()
                }
            }
        }
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase?) {
        context?.let { context ->
            attachmentsAdapter = EntryAttachmentsItemsAdapter(context)
            attachmentsAdapter?.database = database
        }

        attachmentsListView.adapter = attachmentsAdapter
    }

    private fun loadTemplateSettings() {
        context?.let { context ->
            templateView.setFirstTimeAskAllowCopyProtectedFields(PreferencesUtil.isFirstTimeAskAllowCopyProtectedFields(context))
            templateView.setAllowCopyProtectedFields(PreferencesUtil.allowCopyProtectedFields(context))
        }
    }

    private fun assignEntryInfo(entryInfo: EntryInfo?) {
        // Set copy buttons
        templateView.apply {
            setOnAskCopySafeClickListener {
                showClipboardDialog()
            }

            setOnCopyActionClickListener { field ->
                mClipboardHelper?.timeoutCopyToClipboard(
                    TemplateField.getLocalizedName(context, field.name),
                    field.protectedValue.stringValue,
                    field.protectedValue.isProtected
                )
            }
			
			// NEW: Send button handler (wired from TemplateView -> TextFieldView.sendButton)
			setOnSendActionClickListener { field ->
				val ctx = requireContext()
				val address = PreferencesUtil.getOutputDeviceId(ctx)
				if (address.isNullOrBlank()) {
					Toast.makeText(ctx, R.string.msg_select_first, Toast.LENGTH_SHORT).show()
					return@setOnSendActionClickListener
				}

				// Disable the button immediately
				templateView.setSendInProgress(true)

				// Fallback UI timer in case BLE never calls back (e.g., stuck connecting)
				val ui = Handler(Looper.getMainLooper())
				val fallback = Runnable {
					templateView.setSendInProgress(false)
					Toast.makeText(ctx, getString(R.string.msg_send_failed) + ": timeout", Toast.LENGTH_SHORT).show()
				}
				ui.postDelayed(fallback, 12_000) // 12s safety net

				// Nordic UART Service (NUS)
				//val SERVICE_UUID = java.util.UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
				//val CHAR_UUID    = java.util.UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
				
				val payload = field.protectedValue.stringValue.toByteArray(Charsets.UTF_8)

				// Use the global hub (will connect if needed; will NOT disconnect after)
				com.kunzisoft.keepass.receivers.BleHub.writePassword(
					bytes = payload,
					address = address
				) { ok, err ->
					// Re-enable + toast on UI thread and cancel fallback
					ui.post {
						ui.removeCallbacks(fallback)
						templateView.setSendInProgress(false)
						val msg = if (ok) getString(R.string.msg_sent_ok)
								  else getString(R.string.msg_send_failed) + (err?.let { ": $it" } ?: "")
						Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
					}
				}
			}
		
			
        }

        // Populate entry views
        templateView.setEntryInfo(entryInfo)

        // OTP timer updated
        templateView.setOnOtpElementUpdated { otpElementUpdated ->
            mEntryViewModel.onOtpElementUpdated(otpElementUpdated)
        }

        // Manage attachments
        assignAttachments(entryInfo?.attachments ?: listOf())

        // Assign dates
        creationDateView.text = entryInfo?.creationTime?.getDateTimeString(resources)
        modificationDateView.text = entryInfo?.lastModificationTime?.getDateTimeString(resources)

        // TODO Custom data
        // customDataView.text = entryInfo?.customData?.toString()

        // Assign special data
        uuidReferenceView.text = UuidUtil.toHexString(entryInfo?.id)
    }

    private fun showClipboardDialog() {
        context?.let {
            AlertDialog.Builder(it)
                .setMessage(
                    getString(R.string.allow_copy_password_warning) +
                            "\n\n" +
                            getString(R.string.clipboard_warning)
                )
                .create().apply {
                    setButton(AlertDialog.BUTTON_POSITIVE, getText(R.string.enable)) { dialog, _ ->
                        PreferencesUtil.setAllowCopyPasswordAndProtectedFields(context, true)
                        finishDialog(dialog)
                    }
                    setButton(AlertDialog.BUTTON_NEGATIVE, getText(R.string.disable)) { dialog, _ ->
                        PreferencesUtil.setAllowCopyPasswordAndProtectedFields(context, false)
                        finishDialog(dialog)
                    }
                    show()
                }
        }
    }

    private fun finishDialog(dialog: DialogInterface) {
        dialog.dismiss()
        loadTemplateSettings()
        templateView.reload()
    }

    /* -------------
     * Attachments
     * -------------
     */

    private fun assignAttachments(attachments: List<Attachment>) {
        attachmentsContainerView.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        attachmentsAdapter?.assignItems(attachments.map {
            EntryAttachmentState(it, StreamDirection.DOWNLOAD)
        })
        attachmentsAdapter?.onItemClickListener = { item ->
            mEntryViewModel.onAttachmentSelected(item.attachment)
        }
    }

    fun putAttachment(attachmentToDownload: EntryAttachmentState) {
        attachmentsAdapter?.putItem(attachmentToDownload)
    }

    /* -------------
     * Education
     * -------------
     */

    fun firstEntryFieldCopyView(): View? {
        return try {
            templateView.getActionImageView()
        } catch (e: Exception) {
            null
        }
    }

    fun launchEntryCopyEducationAction() {
        val appNameString = getString(R.string.app_name)
        mClipboardHelper?.timeoutCopyToClipboard(appNameString, appNameString)
    }

    companion object {

        fun getInstance(): EntryFragment {
            return EntryFragment().apply {
                arguments = Bundle()
            }
        }
    }
}
