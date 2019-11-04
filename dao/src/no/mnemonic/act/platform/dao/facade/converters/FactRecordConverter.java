package no.mnemonic.act.platform.dao.facade.converters;

import no.mnemonic.act.platform.dao.api.record.FactAclEntryRecord;
import no.mnemonic.act.platform.dao.api.record.FactRecord;
import no.mnemonic.act.platform.dao.api.record.ObjectRecord;
import no.mnemonic.act.platform.dao.cassandra.FactManager;
import no.mnemonic.act.platform.dao.cassandra.ObjectManager;
import no.mnemonic.act.platform.dao.cassandra.entity.*;
import no.mnemonic.act.platform.dao.elastic.FactSearchManager;
import no.mnemonic.act.platform.dao.elastic.criteria.FactExistenceSearchCriteria;
import no.mnemonic.act.platform.dao.elastic.document.FactDocument;
import no.mnemonic.act.platform.dao.elastic.document.ObjectDocument;
import no.mnemonic.commons.logging.Logger;
import no.mnemonic.commons.logging.Logging;
import no.mnemonic.commons.utilities.ObjectUtils;
import no.mnemonic.commons.utilities.collections.CollectionUtils;
import no.mnemonic.commons.utilities.collections.SetUtils;

import javax.inject.Inject;
import java.util.UUID;

import static no.mnemonic.act.platform.dao.cassandra.entity.Direction.FactIsDestination;
import static no.mnemonic.act.platform.dao.cassandra.entity.Direction.FactIsSource;

/**
 * Class for converting {@link FactRecord}s.
 */
public class FactRecordConverter {

  private static final Logger LOGGER = Logging.getLogger(FactRecordConverter.class);

  private final FactSearchManager factSearchManager;
  private final FactManager factManager;
  private final ObjectManager objectManager;
  private final ObjectRecordConverter objectRecordConverter;
  private final FactAclEntryRecordConverter factAclEntryRecordConverter;
  private final FactCommentRecordConverter factCommentRecordConverter;

  @Inject
  public FactRecordConverter(FactSearchManager factSearchManager,
                             FactManager factManager,
                             ObjectManager objectManager,
                             ObjectRecordConverter objectRecordConverter,
                             FactAclEntryRecordConverter factAclEntryRecordConverter,
                             FactCommentRecordConverter factCommentRecordConverter) {
    this.factSearchManager = factSearchManager;
    this.factManager = factManager;
    this.objectManager = objectManager;
    this.objectRecordConverter = objectRecordConverter;
    this.factAclEntryRecordConverter = factAclEntryRecordConverter;
    this.factCommentRecordConverter = factCommentRecordConverter;
  }

  /**
   * Convert {@link FactEntity} to {@link FactRecord}.
   *
   * @param entity Fact to convert
   * @return Converted Fact
   */
  public FactRecord fromEntity(FactEntity entity) {
    if (entity == null) return null;

    // Set all fields directly available on entity.
    FactRecord record = new FactRecord()
            .setId(entity.getId())
            .setTypeID(entity.getTypeID())
            .setValue(entity.getValue())
            .setInReferenceToID(entity.getInReferenceToID())
            .setOrganizationID(entity.getOrganizationID())
            .setOriginID(entity.getOriginID())
            .setAddedByID(entity.getAddedByID())
            .setAccessMode(ObjectUtils.ifNotNull(entity.getAccessMode(), m -> FactRecord.AccessMode.valueOf(m.name())))
            .setConfidence(entity.getConfidence())
            .setTrust(entity.getTrust())
            .setTimestamp(entity.getTimestamp())
            .setLastSeenTimestamp(entity.getLastSeenTimestamp());

    // Populate with records from related entities.
    populateObjects(record, entity);
    populateFlags(record);
    populateFactAcl(record);
    populateFactComments(record);

    return record;
  }

  /**
   * Convert {@link FactRecord} to {@link FactEntity}.
   *
   * @param record Fact to convert
   * @return Converted Fact
   */
  public FactEntity toEntity(FactRecord record) {
    if (record == null) return null;

    FactEntity entity = new FactEntity()
            .setId(record.getId())
            .setTypeID(record.getTypeID())
            .setValue(record.getValue())
            .setInReferenceToID(record.getInReferenceToID())
            .setOrganizationID(record.getOrganizationID())
            .setOriginID(record.getOriginID())
            .setAddedByID(record.getAddedByID())
            .setAccessMode(ObjectUtils.ifNotNull(record.getAccessMode(), m -> AccessMode.valueOf(m.name())))
            .setConfidence(record.getConfidence())
            .setTrust(record.getTrust())
            .setTimestamp(record.getTimestamp())
            .setLastSeenTimestamp(record.getLastSeenTimestamp());

    if (record.getSourceObject() != null) {
      entity.addBinding(new FactEntity.FactObjectBinding()
              .setObjectID(record.getSourceObject().getId())
              .setDirection(record.isBidirectionalBinding() ? Direction.BiDirectional : Direction.FactIsDestination));
    }

    if (record.getDestinationObject() != null) {
      entity.addBinding(new FactEntity.FactObjectBinding()
              .setObjectID(record.getDestinationObject().getId())
              .setDirection(record.isBidirectionalBinding() ? Direction.BiDirectional : Direction.FactIsSource));
    }

    return entity;
  }

  /**
   * Convert {@link FactRecord} to {@link FactDocument}.
   *
   * @param record Fact to convert
   * @return Converted Fact
   */
  public FactDocument toDocument(FactRecord record) {
    if (record == null) return null;

    FactDocument document = new FactDocument()
            .setId(record.getId())
            .setTypeID(record.getTypeID())
            .setValue(record.getValue())
            .setInReferenceTo(record.getInReferenceToID())
            .setOrganizationID(record.getOrganizationID())
            .setOriginID(record.getOriginID())
            .setAddedByID(record.getAddedByID())
            .setAccessMode(ObjectUtils.ifNotNull(record.getAccessMode(), m -> FactDocument.AccessMode.valueOf(m.name())))
            .setConfidence(record.getConfidence())
            .setTrust(record.getTrust())
            .setTimestamp(record.getTimestamp())
            .setLastSeenTimestamp(record.getLastSeenTimestamp())
            .setRetracted(SetUtils.set(record.getFlags()).contains(FactRecord.Flag.RetractedHint))
            .setAcl(SetUtils.set(record.getAcl(), FactAclEntryRecord::getSubjectID));

    if (record.getSourceObject() != null) {
      ObjectDocument.Direction direction = record.isBidirectionalBinding() ? ObjectDocument.Direction.BiDirectional : ObjectDocument.Direction.FactIsDestination;
      document.addObject(toDocument(record.getSourceObject(), direction));
    }

    if (record.getDestinationObject() != null) {
      ObjectDocument.Direction direction = record.isBidirectionalBinding() ? ObjectDocument.Direction.BiDirectional : ObjectDocument.Direction.FactIsSource;
      document.addObject(toDocument(record.getDestinationObject(), direction));
    }

    return document;
  }

  /**
   * Convert {@link FactRecord} to {@link FactExistenceSearchCriteria}.
   *
   * @param record Fact to convert
   * @return Converted criteria
   */
  public FactExistenceSearchCriteria toCriteria(FactRecord record) {
    if (record == null) return null;

    FactExistenceSearchCriteria.Builder criteriaBuilder = FactExistenceSearchCriteria.builder()
            .setFactValue(record.getValue())
            .setFactTypeID(record.getTypeID())
            .setOriginID(record.getOriginID())
            .setOrganizationID(record.getOrganizationID())
            .setAccessMode(record.getAccessMode().name())
            .setConfidence(record.getConfidence())
            .setInReferenceTo(record.getInReferenceToID());

    if (record.getSourceObject() != null) {
      FactExistenceSearchCriteria.Direction direction = record.isBidirectionalBinding() ?
              FactExistenceSearchCriteria.Direction.BiDirectional : FactExistenceSearchCriteria.Direction.FactIsDestination;
      criteriaBuilder.addObject(record.getSourceObject().getId(), direction.name());
    }

    if (record.getDestinationObject() != null) {
      FactExistenceSearchCriteria.Direction direction = record.isBidirectionalBinding() ?
              FactExistenceSearchCriteria.Direction.BiDirectional : FactExistenceSearchCriteria.Direction.FactIsSource;
      criteriaBuilder.addObject(record.getDestinationObject().getId(), direction.name());
    }

    return criteriaBuilder.build();
  }

  private void populateObjects(FactRecord record, FactEntity entity) {
    if (CollectionUtils.isEmpty(entity.getBindings())) return;

    if (CollectionUtils.size(entity.getBindings()) == 1) {
      populateObjectsWithCardinalityOne(record, entity.getBindings().get(0));
    } else if (CollectionUtils.size(entity.getBindings()) == 2) {
      populateObjectsWithCardinalityTwo(record, entity.getBindings().get(0), entity.getBindings().get(1));
    } else {
      // This should never happen as long as create Fact API only allows bindings with cardinality 1 or 2. Log it, just in case.
      LOGGER.warning("Fact is bound to more than two Objects (id = %s). Ignoring Objects in result.", record.getId());
    }
  }

  private void populateObjectsWithCardinalityOne(FactRecord record, FactEntity.FactObjectBinding binding) {
    if (binding.getDirection() == FactIsDestination) {
      record.setSourceObject(convertObject(binding.getObjectID()));
    } else if (binding.getDirection() == FactIsSource) {
      record.setDestinationObject(convertObject(binding.getObjectID()));
    } else {
      // In case of bidirectional binding with cardinality 1 populate source and destination with same Object.
      ObjectRecord object = convertObject(binding.getObjectID());
      record.setSourceObject(object)
              .setDestinationObject(object)
              .setBidirectionalBinding(true);
    }
  }

  private void populateObjectsWithCardinalityTwo(FactRecord record, FactEntity.FactObjectBinding first, FactEntity.FactObjectBinding second) {
    if ((first.getDirection() == FactIsDestination && second.getDirection() == FactIsDestination) ||
            (first.getDirection() == FactIsSource && second.getDirection() == FactIsSource)) {
      // This should never happen as long as create Fact API only allows bindings with cardinality 1 or 2. Log it, just in case.
      LOGGER.warning("Fact is bound to two Objects with the same direction (id = %s). Ignoring Objects in result.", record.getId());
      return;
    }

    if (first.getDirection() == FactIsDestination) {
      // If 'first' has direction 'FactIsDestination' it's the source Object and 'second' the destination Object ...
      record.setSourceObject(convertObject(first.getObjectID()))
              .setDestinationObject(convertObject(second.getObjectID()));
    } else if (second.getDirection() == FactIsDestination) {
      // ... and vice versa. They can't have the same direction!
      record.setSourceObject(convertObject(second.getObjectID()))
              .setDestinationObject(convertObject(first.getObjectID()));
    } else {
      // With bidirectional binding it doesn't matter which Object is source/destination.
      // In order to be consistent always set first as source and second as destination.
      record.setSourceObject(convertObject(first.getObjectID()))
              .setDestinationObject(convertObject(second.getObjectID()))
              .setBidirectionalBinding(true);
    }
  }

  private void populateFlags(FactRecord record) {
    // For historic reasons the indicator whether a Fact has been retracted is only stored in ElasticSearch.
    // This should be moved to Cassandra in order to avoid the call to ElasticSearch and to simplify the code.
    FactDocument document = factSearchManager.getFact(record.getId());
    if (document != null && document.isRetracted()) {
      record.addFlag(FactRecord.Flag.RetractedHint);
    }
  }

  private void populateFactAcl(FactRecord record) {
    for (FactAclEntity entity : factManager.fetchFactAcl(record.getId())) {
      record.addAclEntry(factAclEntryRecordConverter.fromEntity(entity));
    }
  }

  private void populateFactComments(FactRecord record) {
    for (FactCommentEntity entity : factManager.fetchFactComments(record.getId())) {
      record.addComment(factCommentRecordConverter.fromEntity(entity));
    }
  }

  private ObjectRecord convertObject(UUID objectID) {
    return objectRecordConverter.fromEntity(objectManager.getObject(objectID));
  }

  private ObjectDocument toDocument(ObjectRecord record, ObjectDocument.Direction direction) {
    return new ObjectDocument()
            .setId(record.getId())
            .setTypeID(record.getTypeID())
            .setValue(record.getValue())
            .setDirection(direction);
  }
}