package com.afrotransact.seller.port;

import java.util.List;

import com.afrotransact.seller.domain.Department;

/** The catalog's active departments (catalog-service's public category tree), for onboarding and department requests. */
public interface DepartmentDirectory {

    /** Active departments in display order. Throws {@link DirectoryUnavailable} when the catalog cannot be reached. */
    List<Department> departments();

    /** The catalog could not be asked (and nothing recent is cached). */
    class DirectoryUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public DirectoryUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
