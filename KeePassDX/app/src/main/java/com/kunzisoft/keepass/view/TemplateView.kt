package com.kunzisoft.keepass.view

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.core.view.isVisible
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.database.element.Field
import com.kunzisoft.keepass.database.element.security.ProtectedString
import com.kunzisoft.keepass.database.element.template.TemplateAttribute
import com.kunzisoft.keepass.database.element.template.TemplateField
import com.kunzisoft.keepass.database.helper.getLocalizedName
import com.kunzisoft.keepass.database.helper.isStandardPasswordName
import com.kunzisoft.keepass.model.OtpModel
import com.kunzisoft.keepass.otp.OtpElement
import com.kunzisoft.keepass.otp.OtpEntryFields.OTP_TOKEN_FIELD

// larry added
import com.kunzisoft.keepass.settings.PreferencesUtil


class TemplateView @JvmOverloads constructor(context: Context,
                                                 attrs: AttributeSet? = null,
                                                 defStyle: Int = 0)
    : TemplateAbstractView<TextFieldView, TextFieldView, DateTimeFieldView>
        (context, attrs, defStyle) {

    private var mOnAskCopySafeClickListener: (() -> Unit)? = null
    fun setOnAskCopySafeClickListener(listener: (() -> Unit)? = null) {
        this.mOnAskCopySafeClickListener = listener
    }
    private var mOnCopyActionClickListener: ((Field) -> Unit)? = null
    fun setOnCopyActionClickListener(listener: ((Field) -> Unit)? = null) {
        this.mOnCopyActionClickListener = listener
    }

    private var mFirstTimeAskAllowCopyProtectedFields: Boolean = false
    fun setFirstTimeAskAllowCopyProtectedFields(firstTimeAskAllowCopyProtectedFields : Boolean) {
        this.mFirstTimeAskAllowCopyProtectedFields = firstTimeAskAllowCopyProtectedFields
    }

    private var mAllowCopyProtectedFields: Boolean = false
    fun setAllowCopyProtectedFields(allowCopyProtectedFields : Boolean) {
        this.mAllowCopyProtectedFields = allowCopyProtectedFields
    }

	// larry
	private var mOnSendActionClickListener: ((Field) -> Unit)? = null
	fun setOnSendActionClickListener(listener: ((Field) -> Unit)? = null) {
		mOnSendActionClickListener = listener
	}


    override fun preProcessTemplate() {
        headerContainerView.isVisible = false
    }

    override fun buildLinearTextView(templateAttribute: TemplateAttribute,
                                     field: Field): TextFieldView? {
        // Add an action icon if needed
        return context?.let {
            (if (TemplateField.isStandardPasswordName(context, templateAttribute.label))
                PasswordTextFieldView(it)
            else TextFieldView(it)).apply {
                applyFontVisibility(mFontInVisibility)
                setProtection(field.protectedValue.isProtected, mHideProtectedValue)
                label = templateAttribute.alias
                        ?: TemplateField.getLocalizedName(context, field.name)
                setMaxChars(templateAttribute.options.getNumberChars())
                // TODO Linkify
                value = field.protectedValue.stringValue
                // Here the value is often empty

                if (field.protectedValue.isProtected) {
                    textDirection = TEXT_DIRECTION_LTR
                    if (mFirstTimeAskAllowCopyProtectedFields) {
                        setCopyButtonState(TextFieldView.ButtonState.DEACTIVATE)
                        setCopyButtonClickListener { _, _ ->
                            mOnAskCopySafeClickListener?.invoke()
                        }
                    } else {
                        if (mAllowCopyProtectedFields) {
                            setCopyButtonState(TextFieldView.ButtonState.ACTIVATE)
                            setCopyButtonClickListener { label, value ->
                                mOnCopyActionClickListener
                                    ?.invoke(Field(label, ProtectedString(true, value)))
                            }
                        } else {
                            setCopyButtonState(TextFieldView.ButtonState.GONE)
                            setCopyButtonClickListener(null)
                        }
                    }
                } else {
                    setCopyButtonState(TextFieldView.ButtonState.ACTIVATE)
                    setCopyButtonClickListener { label, value ->
                        mOnCopyActionClickListener
                            ?.invoke(Field(label, ProtectedString(false, value)))
                    }
                }
				
				// TemplateView.kt  (inside buildLinearTextView)
				val isPassword = TemplateField.isStandardPasswordName(context, field.name)
				// or, if that helper expects the localized canonical label:
				// val isPassword = TemplateField.isStandardPasswordName(
				//     context, TemplateField.getLocalizedName(context, field.name)
				// )

				if (isPassword) {
					val show = PreferencesUtil.useExternalKeyboardDevice(context) &&
							   !PreferencesUtil.getOutputDeviceId(context).isNullOrBlank()
					setSendButtonVisible(show)
					setOnSendClickListener { label, value ->
						mOnSendActionClickListener?.invoke(
							Field(label, ProtectedString(field.protectedValue.isProtected, value))
						)
					}
				} else {
					setSendButtonVisible(false)
					setOnSendClickListener(null)
				}
				
            }
        }
    }

	// Call this to toggle the password field's send button enabled/disabled
	fun setSendInProgress(inProgress: Boolean) {
		// The password view is tagged with FIELD_PASSWORD_TAG by the template builder
		val passView: TextFieldView? = findViewWithTag(FIELD_PASSWORD_TAG)
		passView?.setSendButtonEnabled(!inProgress)
	}

    override fun buildListItemsView(
        templateAttribute: TemplateAttribute,
        field: Field
    ): TextFieldView? {
        // No special view for selection
        return buildLinearTextView(templateAttribute, field)
    }

    override fun buildDataTimeView(templateAttribute: TemplateAttribute,
                                   field: Field): DateTimeFieldView? {
        return context?.let {
            DateTimeFieldView(it).apply {
                label = TemplateField.getLocalizedName(context, field.name)
                type = templateAttribute.options.getDateFormat()
                isExpirable = templateAttribute.options.getExpirable()
                try {
                    val value = field.protectedValue.toString().trim()
                    activation = value.isNotEmpty()
                } catch (e: Exception) {
                    activation = false
                }
            }
        }
    }

    override fun getActionImageView(): View? {
        return findViewWithTag<TextFieldView?>(FIELD_PASSWORD_TAG)?.getCopyButtonView()
    }

    override fun populateViewsWithEntryInfo(showEmptyFields: Boolean): List<ViewField>  {
        val emptyCustomFields = super.populateViewsWithEntryInfo(false)

        // Hide empty custom fields
        emptyCustomFields.forEach { customFieldId ->
            customFieldId.view.isVisible = false
        }

        removeOtpRunnable()
        mEntryInfo?.let { entryInfo ->
            // Assign specific OTP dynamic view
            entryInfo.otpModel?.let {
                assignOtp(it)
            }
        }

        return emptyCustomFields
    }

    /*
     * OTP Runnable
     */

    private var mOtpRunnable: Runnable? = null
    private var mLastOtpTokenView: View? = null

    fun setOnOtpElementUpdated(listener: ((OtpElement?) -> Unit)?) {
        this.mOnOtpElementUpdated = listener
    }
    private var mOnOtpElementUpdated: ((OtpElement?) -> Unit)? = null

    private fun getOtpTokenView(): TextFieldView? {
        getViewFieldByName(OTP_TOKEN_FIELD)?.let { viewField ->
            val view = viewField.view
            if (view is TextFieldView)
                return view
        }
        return null
    }

    private fun assignOtp(otpModel: OtpModel) {
        getOtpTokenView()?.apply {
            val otpElement = OtpElement(otpModel)
            if (otpElement.token.isEmpty()) {
                setLabel(R.string.entry_otp)
                setValue(R.string.error_invalid_OTP)
                setCopyButtonState(TextFieldView.ButtonState.GONE)
            } else {
                label = otpElement.type.name
                value = otpElement.tokenString
                setCopyButtonState(TextFieldView.ButtonState.ACTIVATE)
                setCopyButtonClickListener { _, _ ->
                    mOnCopyActionClickListener?.invoke(Field(
                        otpElement.type.name,
                        ProtectedString(false, otpElement.token)))
                }
                textDirection = TEXT_DIRECTION_LTR
                mLastOtpTokenView = this
                mOtpRunnable = Runnable {
                    if (otpElement.shouldRefreshToken()) {
                        value = otpElement.tokenString
                    }
                    if (mLastOtpTokenView == null) {
                        mOnOtpElementUpdated?.invoke(null)
                    } else {
                        mOnOtpElementUpdated?.invoke(otpElement)
                        postDelayed(mOtpRunnable, 1000)
                    }
                }
                mOnOtpElementUpdated?.invoke(otpElement)
                post(mOtpRunnable)
            }
        }
    }

    private fun removeOtpRunnable() {
        mLastOtpTokenView?.removeCallbacks(mOtpRunnable)
        mLastOtpTokenView = null
    }
}
