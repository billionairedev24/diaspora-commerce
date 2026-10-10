package com.afrotransact.fulfilment.application;

import java.util.List;

import com.afrotransact.fulfilment.domain.FulfilmentCentre;
import com.afrotransact.fulfilment.port.CentreRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CentreServiceImpl implements CentreService {

    private final FulfilmentAccess access;
    private final CentreRepository centres;

    CentreServiceImpl(FulfilmentAccess access, CentreRepository centres) {
        this.access = access;
        this.centres = centres;
    }

    @Override
    @Transactional
    public FulfilmentCentre save(FulfilmentCentre centre) {
        access.operator();
        return centres.save(centre);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FulfilmentCentre> list() {
        access.opsReader();
        return centres.list();
    }

    @Override
    @Transactional(readOnly = true)
    public FulfilmentCentre get(String id) {
        access.opsReader();
        return centres.find(id).orElseThrow(() -> new NotFoundException("fulfilment.centre-not-found",
                "Fulfilment centre " + id + " not found"));
    }
}
