${parameters.body}<#if parameters.label?has_content>${parameters.label?html}<#elseif parameters.nameValue?has_content>${parameters.nameValue?html}<#else>${getText('reset')}</#if></button>
