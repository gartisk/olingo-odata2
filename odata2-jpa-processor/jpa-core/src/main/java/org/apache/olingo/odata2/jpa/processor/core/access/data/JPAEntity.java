/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 ******************************************************************************/
package org.apache.olingo.odata2.jpa.processor.core.access.data;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.olingo.odata2.api.edm.EdmEntitySet;
import org.apache.olingo.odata2.api.edm.EdmEntityType;
import org.apache.olingo.odata2.api.edm.EdmException;
import org.apache.olingo.odata2.api.edm.EdmNavigationProperty;
import org.apache.olingo.odata2.api.edm.EdmProperty;
import org.apache.olingo.odata2.api.edm.EdmStructuralType;
import org.apache.olingo.odata2.api.edm.EdmTypeKind;
import org.apache.olingo.odata2.api.edm.EdmTyped;
import org.apache.olingo.odata2.api.ep.entry.ODataEntry;
import org.apache.olingo.odata2.api.ep.feed.ODataFeed;
import org.apache.olingo.odata2.jpa.processor.api.exception.ODataJPARuntimeException;
import org.apache.olingo.odata2.jpa.processor.api.model.JPAEdmMapping;

public class JPAEntity {

  private Object jpaEntity = null;
  private EdmEntityType oDataEntityType = null;
  private EdmEntitySet oDataEntitySet = null;
  private Class<?> jpaType = null;
  private HashMap<String, Method> accessModifiersWrite = null;
  private JPAEntityParser jpaEntityParser = null;
  public HashMap<EdmNavigationProperty, EdmEntitySet> inlinedEntities = null;

  public JPAEntity(final EdmEntityType oDataEntityType, final EdmEntitySet oDataEntitySet) {
    this.oDataEntityType = oDataEntityType;
    this.oDataEntitySet = oDataEntitySet;
    try {
      JPAEdmMapping mapping = (JPAEdmMapping) oDataEntityType.getMapping();
      jpaType = mapping.getJPAType();
    } catch (EdmException e) {
      return;
    }
    jpaEntityParser = new JPAEntityParser();
  }

  public void setAccessModifersWrite(final HashMap<String, Method> accessModifiersWrite) {
    this.accessModifiersWrite = accessModifiersWrite;
  }

  public Object getJPAEntity() {
    return jpaEntity;
  }

  @SuppressWarnings("unchecked")
  private void write(final Map<String, Object> oDataEntryProperties, final boolean isCreate)
      throws ODataJPARuntimeException {
    try {

      EdmStructuralType structuralType = null;
      final List<String> keyNames = oDataEntityType.getKeyPropertyNames();

      if (isCreate) {
        jpaEntity = instantiateJPAEntity();
      } else if (jpaEntity == null) {
        throw ODataJPARuntimeException
            .throwException(ODataJPARuntimeException.RESOURCE_NOT_FOUND, null);
      }

      if (accessModifiersWrite == null) {
        accessModifiersWrite =
            jpaEntityParser.getAccessModifiers(jpaEntity, oDataEntityType, JPAEntityParser.ACCESS_MODIFIER_SET);
      }

      if (oDataEntityType == null || oDataEntryProperties == null) {
        throw ODataJPARuntimeException
            .throwException(ODataJPARuntimeException.GENERAL, null);
      }

      final HashMap<String, String> embeddableKeys =
          jpaEntityParser.getJPAEmbeddableKeyMap(jpaEntity.getClass().getName());
      Set<String> propertyNames = null;
      if (embeddableKeys != null) {
        List<String> processedKeys =
            setEmbeddableKeyProperty(embeddableKeys, oDataEntityType.getKeyProperties(), oDataEntryProperties,
                jpaEntity);

        propertyNames = new HashSet<String>();
        propertyNames.addAll(oDataEntryProperties.keySet());
        if (processedKeys.isEmpty()) {
          for (String key : embeddableKeys.keySet()) {
            propertyNames.remove(key);
          }
        } else {
          for (String propertyName : processedKeys) {
            propertyNames.remove(propertyName);
          }
        }
      } else {
        propertyNames = oDataEntryProperties.keySet();
      }

      for (String propertyName : propertyNames) {
        EdmTyped edmTyped = (EdmTyped) oDataEntityType.getProperty(propertyName);

        Method accessModifier = null;

        switch (edmTyped.getType().getKind()) {
        case SIMPLE:
          if (isCreate == false) {
            if (keyNames.contains(edmTyped.getName())) {
              continue;
            }
          }
          accessModifier = accessModifiersWrite.get(propertyName);
          setProperty(accessModifier, jpaEntity, oDataEntryProperties.get(propertyName));
          break;
        case COMPLEX:
          structuralType = (EdmStructuralType) edmTyped.getType();
          accessModifier = accessModifiersWrite.get(propertyName);
          setComplexProperty(accessModifier, jpaEntity,
              structuralType,
              (HashMap<String, Object>) oDataEntryProperties.get(propertyName));
          break;
        case NAVIGATION:
        case ENTITY:
          structuralType = (EdmStructuralType) edmTyped.getType();
          EdmNavigationProperty navProperty = (EdmNavigationProperty) edmTyped;
          accessModifier =
              jpaEntityParser.getAccessModifier(jpaEntity, navProperty,
                  JPAEntityParser.ACCESS_MODIFIER_SET);
          EdmEntitySet edmRelatedEntitySet = oDataEntitySet.getRelatedEntitySet(navProperty);
          List<ODataEntry> relatedEntries = (List<ODataEntry>) oDataEntryProperties.get(propertyName);
          Collection<Object> relatedJPAEntites = instantiateRelatedJPAEntities(jpaEntity, navProperty);
          JPAEntity relatedEntity = new JPAEntity((EdmEntityType) structuralType, edmRelatedEntitySet);
          for (ODataEntry oDataEntry : relatedEntries) {
            relatedEntity.create(oDataEntry);
            relatedJPAEntites.add(relatedEntity.getJPAEntity());
          }

          switch (navProperty.getMultiplicity()) {
          case MANY:
            accessModifier.invoke(jpaEntity, relatedJPAEntites);
            break;
          case ONE:
          case ZERO_TO_ONE:
            accessModifier.invoke(jpaEntity, relatedJPAEntites.iterator().next());
            break;
          }

          if (inlinedEntities == null) {
            inlinedEntities = new HashMap<EdmNavigationProperty, EdmEntitySet>();
          }

          inlinedEntities.put((EdmNavigationProperty) edmTyped, edmRelatedEntitySet);
        default:
          continue;
        }
      }
    } catch (Exception e) {
      throw ODataJPARuntimeException
          .throwException(ODataJPARuntimeException.GENERAL
              .addContent(e.getMessage()), e);
    }
  }

  @SuppressWarnings("unchecked")
  private Collection<Object> instantiateRelatedJPAEntities(final Object jpaEntity,
      final EdmNavigationProperty navProperty)
      throws InstantiationException,
      IllegalAccessException, EdmException, ODataJPARuntimeException, IllegalArgumentException,
      InvocationTargetException {
    Method accessModifier =
        jpaEntityParser.getAccessModifier(jpaEntity, navProperty, JPAEntityParser.ACCESS_MODIFIER_GET);
    Collection<Object> relatedJPAEntities = (Collection<Object>) accessModifier.invoke(jpaEntity);
    if (relatedJPAEntities == null) {
      relatedJPAEntities = new ArrayList<Object>();
    }
    return relatedJPAEntities;
  }

  public void create(final ODataEntry oDataEntry) throws ODataJPARuntimeException {
    if (oDataEntry == null) {
      throw ODataJPARuntimeException
          .throwException(ODataJPARuntimeException.GENERAL, null);
    }
    Map<String, Object> oDataEntryProperties = oDataEntry.getProperties();
    if (oDataEntry.containsInlineEntry()) {
      normalizeInlineEntries(oDataEntryProperties);
    }
    write(oDataEntryProperties, true);
  }

  public void create(final Map<String, Object> oDataEntryProperties) throws ODataJPARuntimeException {
    normalizeInlineEntries(oDataEntryProperties);
    write(oDataEntryProperties, true);
  }

  public void update(final ODataEntry oDataEntry) throws ODataJPARuntimeException {
    if (oDataEntry == null) {
      throw ODataJPARuntimeException
          .throwException(ODataJPARuntimeException.GENERAL, null);
    }
    Map<String, Object> oDataEntryProperties = oDataEntry.getProperties();
    if (oDataEntry.containsInlineEntry()) {
      normalizeInlineEntries(oDataEntryProperties);
    }
    write(oDataEntryProperties, false);
  }

  public void update(final Map<String, Object> oDataEntryProperties) throws ODataJPARuntimeException {
    normalizeInlineEntries(oDataEntryProperties);
    write(oDataEntryProperties, false);
  }

  public HashMap<EdmNavigationProperty, EdmEntitySet> getInlineJPAEntities() {
    return inlinedEntities;
  }

  public void setJPAEntity(final Object jpaEntity) {
    this.jpaEntity = jpaEntity;
  }

  @SuppressWarnings("unchecked")
  protected void setComplexProperty(Method accessModifier, final Object jpaEntity,
      final EdmStructuralType edmComplexType, final HashMap<String, Object> propertyValue)
      throws EdmException, IllegalAccessException, IllegalArgumentException, InvocationTargetException,
      InstantiationException, ODataJPARuntimeException {

    JPAEdmMapping mapping = (JPAEdmMapping) edmComplexType.getMapping();
    Object embeddableObject = mapping.getJPAType().newInstance();
    accessModifier.invoke(jpaEntity, embeddableObject);

    HashMap<String, Method> accessModifiers =
        jpaEntityParser.getAccessModifiers(embeddableObject, edmComplexType, JPAEntityParser.ACCESS_MODIFIER_SET);

    for (String edmPropertyName : edmComplexType.getPropertyNames()) {
      EdmTyped edmTyped = (EdmTyped) edmComplexType.getProperty(edmPropertyName);
      accessModifier = accessModifiers.get(edmPropertyName);
      if (edmTyped.getType().getKind().toString().equals(EdmTypeKind.COMPLEX.toString())) {
        EdmStructuralType structualType = (EdmStructuralType) edmTyped.getType();
        setComplexProperty(accessModifier, embeddableObject, structualType, (HashMap<String, Object>) propertyValue
            .get(edmPropertyName));
      } else {
        setProperty(accessModifier, embeddableObject, propertyValue.get(edmPropertyName));
      }
    }
  }

  protected void setProperty(final Method method, final Object entity, final Object entityPropertyValue) throws
      IllegalAccessException, IllegalArgumentException, InvocationTargetException {
    if (entityPropertyValue != null) {
      Class<?> parameterType = method.getParameterTypes()[0];
      if (parameterType.equals(char[].class)) {
        char[] characters = ((String) entityPropertyValue).toCharArray();
        method.invoke(entity, characters);
      } else if (parameterType.equals(char.class)) {
        char c = ((String) entityPropertyValue).charAt(0);
        method.invoke(entity, c);
      } else if (parameterType.equals(Character[].class)) {
        Character[] characters = JPAEntityParser.toCharacterArray((String) entityPropertyValue);
        method.invoke(entity, (Object) characters);
      } else if (parameterType.equals(Character.class)) {
        Character c = Character.valueOf(((String) entityPropertyValue).charAt(0));
        method.invoke(entity, c);
      } else {
        method.invoke(entity, entityPropertyValue);
      }
    }
  }

  protected List<String> setEmbeddableKeyProperty(final HashMap<String, String> embeddableKeys,
      final List<EdmProperty> oDataEntryKeyProperties,
      final Map<String, Object> oDataEntryProperties, final Object entity)
      throws ODataJPARuntimeException, EdmException, IllegalAccessException, IllegalArgumentException,
      InvocationTargetException, InstantiationException {

    HashMap<String, Object> embeddableObjMap = new HashMap<String, Object>();
    List<EdmProperty> leftODataEntryKeyProperties = new ArrayList<EdmProperty>();
    HashMap<String, String> leftEmbeddableKeys = new HashMap<String, String>();
    List<String> processedKeys = new ArrayList<String>();

    for (EdmProperty edmProperty : oDataEntryKeyProperties) {
      if (oDataEntryProperties.containsKey(edmProperty.getName()) == false) {
        continue;
      }

      String edmPropertyName = edmProperty.getName();
      String embeddableKeyNameComposite = embeddableKeys.get(edmPropertyName);
      if (embeddableKeyNameComposite == null) {
        continue;
      }
      String embeddableKeyNameSplit[] = embeddableKeyNameComposite.split("\\.");
      String methodPartName = null;
      Method method = null;
      Object embeddableObj = null;

      if (embeddableObjMap.containsKey(embeddableKeyNameSplit[0]) == false) {
        methodPartName = embeddableKeyNameSplit[0];
        method = jpaEntityParser.getAccessModifierSet(entity, methodPartName);
        embeddableObj = method.getParameterTypes()[0].newInstance();
        method.invoke(entity, embeddableObj);
        embeddableObjMap.put(embeddableKeyNameSplit[0], embeddableObj);
      } else {
        embeddableObj = embeddableObjMap.get(embeddableKeyNameSplit[0]);
      }

      if (embeddableKeyNameSplit.length == 2) {
        methodPartName = embeddableKeyNameSplit[1];
        method = jpaEntityParser.getAccessModifierSet(embeddableObj, methodPartName);
        Object simpleObj = oDataEntryProperties.get(edmProperty.getName());
        method.invoke(embeddableObj, simpleObj);
      } else if (embeddableKeyNameSplit.length > 2) { // Deeply nested
        leftODataEntryKeyProperties.add(edmProperty);
        leftEmbeddableKeys
            .put(edmPropertyName, embeddableKeyNameComposite.split(embeddableKeyNameSplit[0] + ".", 2)[1]);
        processedKeys.addAll(setEmbeddableKeyProperty(leftEmbeddableKeys, leftODataEntryKeyProperties,
            oDataEntryProperties, embeddableObj));
      }
      processedKeys.add(edmPropertyName);

    }
    return processedKeys;
  }

  protected Object instantiateJPAEntity() throws InstantiationException, IllegalAccessException {
    if (jpaType == null) {
      throw new InstantiationException();
    }

    return jpaType.newInstance();
  }

  private void normalizeInlineEntries(final Map<String, Object> oDataEntryProperties) throws ODataJPARuntimeException {
    List<ODataEntry> entries = null;
    try {
      for (String navigationPropertyName : oDataEntityType.getNavigationPropertyNames()) {
        Object inline = oDataEntryProperties.get(navigationPropertyName);
        if (inline instanceof ODataFeed) {
          entries = ((ODataFeed) inline).getEntries();
        } else if (inline instanceof ODataEntry) {
          entries = new ArrayList<ODataEntry>();
          entries.add((ODataEntry) inline);
        }
        if (entries != null) {
          oDataEntryProperties.put(navigationPropertyName, entries);
          entries = null;
        }
      }
    } catch (EdmException e) {
      throw ODataJPARuntimeException
          .throwException(ODataJPARuntimeException.GENERAL
              .addContent(e.getMessage()), e);
    }
  }
}