package com.cadence.flagservice.flag.mapper;

import com.cadence.core.model.FlagDefinition;
import com.cadence.flagservice.flag.domain.FeatureFlag;
import com.cadence.flagservice.flag.dto.FlagResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FlagMapper {

    public FlagResponse toResponse(FeatureFlag flag) {
        return FlagResponse.from(flag);
    }

    public List<FlagResponse> toResponses(List<FeatureFlag> flags) {
        return flags.stream().map(FlagResponse::from).toList();
    }

    /** The lean shape the SDK caches. Deliberately omits createdBy/description: the data plane has no use for them. */
    public FlagDefinition toDefinition(FeatureFlag flag) {
        return flag.toDefinition();
    }

    public List<FlagDefinition> toDefinitions(List<FeatureFlag> flags) {
        return flags.stream().map(FeatureFlag::toDefinition).toList();
    }
}
