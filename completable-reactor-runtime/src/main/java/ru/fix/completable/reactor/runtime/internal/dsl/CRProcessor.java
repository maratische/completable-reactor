package ru.fix.completable.reactor.runtime.internal.dsl;

import lombok.Data;
import lombok.EqualsAndHashCode;
import ru.fix.completable.reactor.api.ReactorGraphModel;
import ru.fix.completable.reactor.runtime.dsl.Processor;
import ru.fix.completable.reactor.runtime.internal.CRProcessingItem;

/**
 *
 * @author Kamil Asfandiyarov
 */
@EqualsAndHashCode(doNotUseGetters = true)
@Data
public class CRProcessor<PayloadType> implements Processor<PayloadType>, CRProcessingItem {

    final ReactorGraphModel.Identity identity = new ReactorGraphModel.Identity()
            .setType(ReactorGraphModel.Identity.Type.PROCESSOR);

    final CRProcessorDescription<PayloadType> processorDescription;

    CRProcessor(CRProcessorDescription<PayloadType> description) {
        this.processorDescription = description;
        this.identity
                .setClassName(processorDescription.processorType.getSimpleName());
    }

    public CRProcessor<PayloadType> setName(String name){
        this.identity.setName(name);
        return this;
    }

    @Override
    public String getProfilingName() {
        return processorDescription.processorType.getSimpleName();
    }

    @Override
    public String getDebugName() {
        return identity.toString();
    }
}
