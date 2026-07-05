package com.bit.api.openai

import com.bit.api.Constants

class DeepSeekProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_DEEPSEEK
    override val defaultBaseUrl: String = "https://api.deepseek.com"
}
