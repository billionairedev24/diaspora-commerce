package com.afrotransact.fulfilment.domain;

/**
 * One seller's part of a pre-order shipment (S264): a window's package mixes sellers, so each seller packs a labelled
 * bag per order. The first bag carries the shipment's own label; the driver scans each bag at the seller and again at
 * the drop.
 *
 * @param shipmentId  orders-service shipment
 * @param sellerOrgId seller organisation
 * @param sellerName  display name
 * @param label       ATS label of the bag
 * @param units       units in the bag
 */
public record RunBag(String shipmentId, String sellerOrgId, String sellerName, String label, int units) {
}
